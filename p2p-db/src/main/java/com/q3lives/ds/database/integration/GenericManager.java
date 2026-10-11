
package com.q3lives.ds.database.integration;


import java.io.IOException;
import java.io.File;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.query.OrderByProp;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.DsDatabaseServer;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.index.DsEqIndexStore;
import com.q3lives.ds.database.schema.EntityIndexUtil;
import com.q3lives.ds.database.schema.EntitySchemaUtil;
import com.q3lives.ds.util.DateUtil;
import com.q3lives.ds.util.DsPathUtil;
import com.q3lives.ds.util.MyBeanUtils;
import com.q3lives.ds.util.MyDsDatabaseUtil;
import com.q3lives.ds.util.OrderWrapper;
import com.q3lives.ds.util.StringUtils;
import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.distance.DistanceUtils;
import com.spatial4j.core.io.GeohashUtils;
import com.spatial4j.core.shape.Rectangle;

/**
 * 所有DAO接口的基类
 *
 * @author karl
 * @since 2020-10-13
 * @param <T extends DsTableAdapter>  泛型
 */
public class GenericManager<T extends DsTableAdapter>{
    
    private volatile Class<T> persistentClass;
    private final DsDatabaseLocal db;
    private final DsDatabaseServer distClient;
    private volatile DsHashSet idSet;
    private final Object initLock = new Object();
    private volatile long readTimestamp = Long.MAX_VALUE;

    private volatile Map<String, String> field2ColMap;
    private volatile Map<String, Field> fieldsMap;
    private volatile Map<String, Field> col2FieldMap;
    private volatile List<Field> persistentFields;
    private volatile Map<String, IndexedColInfo> indexedColInfo;
    private volatile List<CompositeIndexInfo> compositeIndexInfo;
    private volatile String schemaId;
    private volatile boolean hasIndexedColumns;
    private volatile boolean hasCompositeIndexes;

    // ---- P17 T3/T4: 复合索引 字典序 range/order/group 进程内 缓存（懒构建 全表 扫 1×，重启 重 建） ----
    private final Object compositeRangeLock = new Object();
    private volatile Map<CompositeIndexInfo, java.util.NavigableMap<byte[], List<Long>>> compRangeIndexMaps;
    private static final ThreadLocal<int[]> SLICE_HINT = ThreadLocal.withInitial(() -> new int[]{-1, -1}); // [start,end]
    private static final ThreadLocal<Boolean> SKIP_SORT_HINT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // ---- P17 T5: 列存 EqIndexStore String Range Bridge（运行时 反射 0 编译期 耦合） ----
    private volatile StringIndexBridge stringBridge;
    private static final class StringIndexBridge {
        final Class<?> eqClz;
        final java.lang.reflect.Constructor<?> eqCtor;
        final java.lang.invoke.MethodHandle mhAdd;
        final java.lang.invoke.MethodHandle mhRemove;
        final java.lang.invoke.MethodHandle mhFindEq;
        final java.lang.invoke.MethodHandle mhBetween;
        final java.lang.invoke.MethodHandle mhGt;
        final java.lang.invoke.MethodHandle mhGe;
        final java.lang.invoke.MethodHandle mhLt;
        final java.lang.invoke.MethodHandle mhLe;
        final java.lang.invoke.MethodHandle mhCreateOrReplace;
        final Object storeInstance;
        final Map<String, Long> colIds = new java.util.concurrent.ConcurrentHashMap<>();

        StringIndexBridge(File dbRoot, Class<?> persistentClz,
                          Map<String, IndexedColInfo> indexedCols) throws Throwable {
            this.eqClz = Class.forName("com.q3lives.ds.database.columnar.index.EqIndexStore");
            this.eqCtor = eqClz.getDeclaredConstructor(File.class);
            this.eqCtor.setAccessible(true);
            this.storeInstance = eqCtor.newInstance(dbRoot);
            java.lang.invoke.MethodHandles.Lookup lk = java.lang.invoke.MethodHandles.lookup();
            java.lang.reflect.Method mAdd = eqClz.getDeclaredMethod("add", String.class, long.class, byte[].class, long.class);
            mAdd.setAccessible(true);
            this.mhAdd = lk.unreflect(mAdd);
            java.lang.reflect.Method mRem = eqClz.getDeclaredMethod("remove", String.class, long.class, byte[].class, long.class);
            mRem.setAccessible(true);
            this.mhRemove = lk.unreflect(mRem);
            java.lang.reflect.Method mEq = eqClz.getDeclaredMethod("findRowIds", String.class, long.class, byte[].class, int.class);
            mEq.setAccessible(true);
            this.mhFindEq = lk.unreflect(mEq);
            java.lang.reflect.Method mBt = eqClz.getDeclaredMethod("findRowIdsBetween",
                    String.class, long.class, byte[].class, boolean.class, byte[].class, boolean.class, boolean.class, int.class);
            mBt.setAccessible(true);
            this.mhBetween = lk.unreflect(mBt);
            java.lang.reflect.Method mGt = eqClz.getDeclaredMethod("findRowIdsGt", String.class, long.class, byte[].class, boolean.class, int.class);
            mGt.setAccessible(true);
            this.mhGt = lk.unreflect(mGt);
            java.lang.reflect.Method mGe = eqClz.getDeclaredMethod("findRowIdsGe", String.class, long.class, byte[].class, boolean.class, int.class);
            mGe.setAccessible(true);
            this.mhGe = lk.unreflect(mGe);
            java.lang.reflect.Method mLt = eqClz.getDeclaredMethod("findRowIdsLt", String.class, long.class, byte[].class, boolean.class, int.class);
            mLt.setAccessible(true);
            this.mhLt = lk.unreflect(mLt);
            java.lang.reflect.Method mLe = eqClz.getDeclaredMethod("findRowIdsLe", String.class, long.class, byte[].class, boolean.class, int.class);
            mLe.setAccessible(true);
            this.mhLe = lk.unreflect(mLe);
            java.lang.reflect.Method mCr = eqClz.getDeclaredMethod("createOrReplace", String.class, String.class, long.class);
            mCr.setAccessible(true);
            this.mhCreateOrReplace = lk.unreflect(mCr);
            String entityClassName = persistentClz.getName();
            for (Map.Entry<String, IndexedColInfo> e : indexedCols.entrySet()) {
                IndexedColInfo info = e.getValue();
                if (info.valueKind != DsEqIndexStore.IndexedValueKind.STRING) continue;
                long cid = ((long) entityClassName.hashCode() & 0x7FFFFFFFL) << 32
                        | ((long) info.columnName.hashCode() & 0x7FFFFFFFL);
                colIds.put(info.columnName, cid);
                try {
                    mhCreateOrReplace.invoke(storeInstance, entityClassName, info.columnName, cid);
                } catch (Throwable ignored) {
                }
            }
        }

        long colId(String col) {
            Long c = colIds.get(col);
            return c == null ? 0L : c;
        }
    }

    private StringIndexBridge ensureStringBridge() {
        StringIndexBridge b = stringBridge;
        if (b != null) return b;
        synchronized (initLock) {
            b = stringBridge;
            if (b != null) return b;
            if (indexedColInfo == null || persistentClass == null) return null;
            boolean hasString = false;
            for (IndexedColInfo info : indexedColInfo.values()) {
                if (info.valueKind == DsEqIndexStore.IndexedValueKind.STRING) { hasString = true; break; }
            }
            if (!hasString) return null;
            try {
                b = new StringIndexBridge(db.getRoot(), persistentClass, indexedColInfo);
            } catch (Throwable t) {
                return null;
            }
            stringBridge = b;
            return b;
        }
    }

    private void bridgeAddStringIndexes(T entity) {
        if (entity == null || entity.getId() == null || entity.getId() == 0L) return;
        StringIndexBridge b = ensureStringBridge();
        if (b == null) return;
        Map<String, IndexedColInfo> ici = indexedColInfo;
        if (ici == null || ici.isEmpty()) return;
        String entityClassName = persistentClass.getName();
        try {
            for (IndexedColInfo info : ici.values()) {
                if (info.valueKind != DsEqIndexStore.IndexedValueKind.STRING) continue;
                long cid = b.colId(info.columnName);
                if (cid == 0L) continue;
                Object rawVal = info.field.get(entity);
                String s = (rawVal == null) ? null : String.valueOf(coerceStatic(rawVal, String.class));
                byte[] sb = (s == null) ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
                b.mhAdd.invoke(b.storeInstance, entityClassName, cid, sb, entity.getId());
            }
        } catch (Throwable t) {
            // bridge 不 影响 主 流 程 成 功
        }
    }

    private void bridgeRemoveStringIndexes(T entity) {
        if (entity == null || entity.getId() == null || entity.getId() == 0L) return;
        StringIndexBridge b = ensureStringBridge();
        if (b == null) return;
        Map<String, IndexedColInfo> ici = indexedColInfo;
        if (ici == null || ici.isEmpty()) return;
        String entityClassName = persistentClass.getName();
        try {
            for (IndexedColInfo info : ici.values()) {
                if (info.valueKind != DsEqIndexStore.IndexedValueKind.STRING) continue;
                long cid = b.colId(info.columnName);
                if (cid == 0L) continue;
                Object rawVal = info.field.get(entity);
                String s = (rawVal == null) ? null : String.valueOf(coerceStatic(rawVal, String.class));
                byte[] sb = (s == null) ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
                b.mhRemove.invoke(b.storeInstance, entityClassName, cid, sb, entity.getId());
            }
        } catch (Throwable t) {
        }
    }

    private static final class IndexedColInfo {
        final String columnName;
        final Field field;
        final Class<?> fieldType;
        final DsEqIndexStore.IndexedValueKind valueKind;
        IndexedColInfo(String columnName, Field field, Class<?> fieldType, DsEqIndexStore.IndexedValueKind valueKind) {
            this.columnName = columnName;
            this.field = field;
            this.fieldType = fieldType;
            this.valueKind = valueKind;
        }
    }

    private static final class CompositeIndexInfo {
        final String name;
        final List<IndexedColInfo> columns;
        CompositeIndexInfo(String name, List<IndexedColInfo> columns) {
            this.name = name;
            this.columns = columns;
        }
    }
    
    public GenericManager() {
        this.db = DsDatabaseLocal.load();
        this.distClient = null;
    }
    
    public GenericManager(Class<T> persistentClass) {
        this();
        this.persistentClass = persistentClass;
    }

    public GenericManager(Class<T> persistentClass, DsDatabaseServer distributedClient) {
        this.db = null;
        this.distClient = Objects.requireNonNull(distributedClient, "distributedClient cannot be null");
        this.persistentClass = persistentClass;
    }

    public GenericManager(Class<T> persistentClass, DsDatabaseLocal localDb, int serverId, int partitionId) {
        this.db = Objects.requireNonNull(localDb, "localDb cannot be null");
        if (serverId != localDb.serverId() || partitionId != localDb.partitionId()) {
            throw new IllegalArgumentException("localDb serverId/partitionId mismatch: ("
                    + localDb.serverId() + "," + localDb.partitionId() + ") vs ("
                    + serverId + "," + partitionId + ")");
        }
        this.distClient = null;
        this.persistentClass = persistentClass;
    }

    private boolean isDistributed() {
        return distClient != null;
    }

    public Class<? extends DsTableAdapter> getPersistentClass() {
        return persistentClass;
    }

    public void setPersistentClass(Class<? extends DsTableAdapter> persistentClass) {
        @SuppressWarnings("unchecked")
        Class<T> c = (Class<T>) persistentClass;
        this.persistentClass = c;
        this.idSet = null;
        this.field2ColMap = null;
        this.fieldsMap = null;
        this.col2FieldMap = null;
        this.persistentFields = null;
    }

    public long getReadTimestamp() {
        return readTimestamp;
    }

    public void setReadTimestamp(long readTimestamp) {
        this.readTimestamp = readTimestamp;
    }

    private void ensureInit() {
        if (isDistributed()) {
            throw new UnsupportedOperationException("distributed mode: use primitive routing APIs (saveOrUpdate/getById/removeById/existsById/findList(QueryWrapper)/count(QueryWrapper))");
        }
        if (idSet != null && field2ColMap != null && fieldsMap != null && col2FieldMap != null
                && persistentFields != null && indexedColInfo != null && compositeIndexInfo != null) {
            return;
        }
        synchronized (initLock) {
            if (idSet != null && field2ColMap != null && fieldsMap != null && col2FieldMap != null
                    && persistentFields != null && indexedColInfo != null && compositeIndexInfo != null) {
                return;
            }
            if (persistentClass == null) {
                throw new IllegalStateException("persistentClass is not set");
            }
            // Always reuse the db-internal classIdSet instance. Otherwise a 2nd DsHashSet
            // opened on the same idsFile may not observe writes performed via the
            // db.putEntity -> classIdSet path (binlog replay / cross-instance replication).
            idSet = db.getIdSetForClass(persistentClass);
            initFieldCaches();
            EntitySchemaUtil.SchemaDef curSchema = EntitySchemaUtil.schemaOf(persistentClass);
            this.schemaId = curSchema.schemaId;
            Map<String, IndexedColInfo> idxCol = new LinkedHashMap<>();
            for (EntitySchemaUtil.ColumnDef col : curSchema.getColumns()) {
                Field f = col.declaredField;
                f.setAccessible(true);
                if (col.indexed) {
                    idxCol.put(col.name, new IndexedColInfo(col.name, f, f.getType(), resolveIndexedValueKind(f.getType())));
                }
            }
            this.indexedColInfo = idxCol.isEmpty() ? Collections.emptyMap() : idxCol;
            this.hasIndexedColumns = !this.indexedColInfo.isEmpty();

            List<CompositeIndexInfo> cidx = new ArrayList<>();
            for (EntitySchemaUtil.CompositeIndexDef ci : curSchema.getCompositeIndexes()) {
                List<IndexedColInfo> cols = new ArrayList<>(ci.columns.size());
                for (EntitySchemaUtil.ColumnDef c : ci.columns) {
                    IndexedColInfo info = idxCol.get(c.name);
                    if (info == null) {
                        Field f = c.declaredField;
                        f.setAccessible(true);
                        info = new IndexedColInfo(c.name, f, f.getType(), resolveIndexedValueKind(f.getType()));
                    }
                    cols.add(info);
                }
                cidx.add(new CompositeIndexInfo(ci.name, cols));
            }
            this.compositeIndexInfo = cidx.isEmpty() ? Collections.emptyList() : cidx;
            this.hasCompositeIndexes = !this.compositeIndexInfo.isEmpty();
        }
    }
    
    private static DsEqIndexStore.IndexedValueKind resolveIndexedValueKind(Class<?> type) {
        if (type == String.class) return DsEqIndexStore.IndexedValueKind.STRING;
        if (type == Long.class || type == long.class || type == Integer.class || type == int.class
                || type == Short.class || type == short.class || type == Byte.class || type == byte.class
                || type == Boolean.class || type == boolean.class || type == Date.class
                || type == java.sql.Date.class || type == LocalDate.class || type == LocalDateTime.class) {
            return DsEqIndexStore.IndexedValueKind.LONG;
        }
        return null;
    }
    
    private void initFieldCaches() {
        List<Field> all = MyBeanUtils.getAllPersistentFields(persistentClass);
        List<Field> pers = new ArrayList<>();
        Map<String, String> f2c = new LinkedHashMap<>();
        Map<String, Field> fMap = new LinkedHashMap<>();
        Map<String, Field> c2f = new LinkedHashMap<>();
        for (Field f : all) {
            DsField ann = f.getAnnotation(DsField.class);
            if (ann == null) {
                continue;
            }
            String col = ann.name();
            if (col == null || col.isBlank()) {
                col = f.getName();
            }
            pers.add(f);
            f2c.put(f.getName(), col);
            fMap.put(f.getName(), f);
            c2f.put(col, f);
        }
        field2ColMap = f2c;
        fieldsMap = fMap;
        col2FieldMap = c2f;
        persistentFields = pers;
    }
   
    /**
     * 根据 ID 删除
     *
     * @param id 主键ID
     * @return 
     */
   
    public int removeById(Serializable id) {
        long v = toLongId(id);
        if (isDistributed()) {
            try {
                return distClient.removeTable(persistentClass, v, true) ? 1 : 0;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ensureInit();
        try {
            T prev = db.getTable(persistentClass, v, Long.MAX_VALUE);
            if (prev != null) bridgeRemoveStringIndexes(prev);
            db.removeTable(persistentClass, v, true);
            idSet.remove(v);
            return 1;
        } catch (Throwable t) {
            if (t instanceof IOException) throw new RuntimeException(t);
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new RuntimeException(t);
        }
    }
    
     /**
     * 根据 ID 选择修改
     *
     * @param entity 实体对象
     * @return 
     */
   
    public int updateById(T entity) {
        if (entity == null || entity.getId() == null || entity.getId() == 0L) {
            throw new IllegalArgumentException("missing id");
        }
        if (isDistributed()) {
            try {
                distClient.putTable(entity, true);
                return 1;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ensureInit();
        try {
            T prev = db.getTable(persistentClass, entity.getId(), Long.MAX_VALUE);
            if (prev != null) bridgeRemoveStringIndexes(prev);
            db.putTable(entity, true);
            idSet.add(entity.getId());
            bridgeAddStringIndexes(entity);
            return 1;
        } catch (Throwable t) {
            if (t instanceof IOException) throw new RuntimeException(t);
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new RuntimeException(t);
        }
    }
    
    /**
     * 删除（根据ID 批量删除）
     *
     * @param idList 主键ID列表
     * @return 
     */
   
    public int removeByIds(Collection idList) {
        ensureInit();
        if (idList == null || idList.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (Object o : idList) {
            removed += removeById((Serializable) o);
        }
        return removed;
    }
    
    /**
     * 根据 ID 查询
     *
     * @param id 主键ID
     * @return 
     */
   
    public T getById(Serializable id) {
        long v = toLongId(id);
        if (isDistributed()) {
            try {
                return distClient.getTable(persistentClass, v, false);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ensureInit();
        return db.getTable(persistentClass, v, readTimestamp);
    }
    
    /**
     * TableId 注解存在更新记录，否插入一条记录
     *
     * @param entity 实体对象
     * @return 
     */
   
    public int saveOrUpdate(T entity){
        if (entity == null) {
            return 0;
        }
        if (isDistributed()) {
            try {
                long id = distClient.putTable(entity, true);
                if (entity.getId() == null || entity.getId() == 0L) {
                    entity.setId(id);
                }
                return 1;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ensureInit();
        try {
            boolean existed = entity.getId() != null && entity.getId() != 0L
                    && idSet != null && idSet.contains(entity.getId());
            long prevId = existed && entity.getId() != null ? entity.getId() : 0L;
            T prev = existed ? db.getTable(persistentClass, prevId, Long.MAX_VALUE) : null;
            long id = db.putTable(entity, true);
            if (entity.getId() == null || entity.getId() == 0L) entity.setId(id);
            idSet.add(id);
            if (prev != null) bridgeRemoveStringIndexes(prev);
            bridgeAddStringIndexes(entity);
            return 1;
        } catch (Throwable t) {
            if (t instanceof IOException) throw new RuntimeException(t);
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new RuntimeException(t);
        }
    }
    
     public Object getProperty(T entity, String propName) {
//        Field f = getFieldsMap().get(propName);
//        if (f == null) {
//            throw new RuntimeException(entity.getClass() + " not find persistent property:" + propName);
//        }
//        try {
//            return f.get(entity);
//        } catch (Exception ex) {
//            throw new RuntimeException(ex.getMessage());
//        }
        return null;

    }

     public void setProperty(T entity, String propName, Object val) {
//        Field f = getFieldsMap().get(propName);
//        if (f == null) {
//            throw new RuntimeException(entity.getClass() + " not find persistent property:" + propName);
//        }
//        try {
//            f.set(entity, val);
//        } catch (Exception ex) {
//            throw new RuntimeException(ex.getMessage());
//        }
    }

    /**
     * 按筛选条件更新对象集合
     *
     * @param o
     * @param params
     * @return
     */
     public int updateByMap(T o, Map<String, Object> params) {
        ensureInit();
        if (o == null || params == null || params.isEmpty()) {
            return 0;
        }
        List<T> list = findListByMap(params);
        int updated = 0;
        for (T e : list) {
            MyBeanUtils.copyBeanNotNull2Bean(o, e);
            updated += updateById(e);
        }
        return updated;
    }

    /**
     * 按筛选条件删除对象集合
     *
     * @param params
     * @return
     */
     public int deleteByMap(Map<String, Object> params) {
        ensureInit();
        if (params == null || params.isEmpty()) {
            return 0;
        }
        List<T> list = findListByMap(params);
        int deleted = 0;
        for (T e : list) {
            deleted += removeById(e.getId());
        }
        return deleted;
    }

     public boolean existsById(Object id) {
        long v = toLongId((Serializable) id);
        if (isDistributed()) {
            try {
                return distClient.exists(persistentClass, v);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ensureInit();
        return idSet.contains(v);
    }

     private static long toLongId(Serializable id) {
         if (id == null) {
             throw new IllegalArgumentException("id is null");
         }
         if (id instanceof Long l) {
             return l;
         }
         if (id instanceof Integer i) {
             return i.longValue();
         }
         return Long.parseLong(id.toString());
     }

     public QueryWrapper buildQueryWrapper(Map<String, ?> conds) throws NoSuchFieldException {
        QueryWrapper<T> queryWrapper = new QueryWrapper<>();
        Map<String, String> fieldsMap = getField2ColMap();
        //处理搜索条件

        //处理地理范围半径查询
        Object val = conds.get("geoSearchRadius");
        if (null != val) {
            Integer radius;
            Double lon, lat;
            if (val instanceof Integer) {
                radius = (Integer) val;
            } else {
                radius = Integer.parseInt(val.toString());
            }

            val = conds.get("lon");//获取经度
            if (null != val) {
                if (val instanceof Double) {
                    lon = (Double) val;
                } else {
                    lon = Double.parseDouble(val.toString());
                }
                val = conds.get("lat");//获取纬度
                if (null != val) {
                    if (val instanceof Double) {
                        lat = (Double) val;
                    } else {
                        lat = Double.parseDouble(val.toString());
                    }
                    SpatialContext geo = SpatialContext.GEO;
                    Rectangle rectangle = geo.getDistCalc().calcBoxByDistFromPt(
                            geo.makePoint(lon, lat), radius * DistanceUtils.KM_TO_DEG, geo, null);
//                    System.out.println(rectangle.getMinX() + "-" + rectangle.getMaxX());// 经度范围
//                    System.out.println(rectangle.getMinY() + "-" + rectangle.getMaxY());// 纬度范围
                    queryWrapper.between("lon", rectangle.getMinX(), rectangle.getMaxX());
                    queryWrapper.between("lat", rectangle.getMinY(), rectangle.getMaxY());
                }
            }
        }

        //处理地理范围geohash查询
        val = conds.get("geoHashLength");
        if (null != val) {
            Integer length;
            Double lon, lat;
            if (val instanceof Integer) {
                length = (Integer) val;
            } else {
                length = Integer.parseInt(val.toString());
            }

            val = conds.get("lon");//获取经度
            if (null != val) {
                if (val instanceof Double) {
                    lon = (Double) val;
                } else {
                    lon = Double.parseDouble(val.toString());
                }
                val = conds.get("lat");//获取纬度
                if (null != val) {
                    if (val instanceof Double) {
                        lat = (Double) val;
                    } else {
                        lat = Double.parseDouble(val.toString());
                    }
                    String geoCode = GeohashUtils.encodeLatLon(lat, lon, length);
                    queryWrapper.likeLeft("geo_code", geoCode);
                }
            }
        }

        //处理is null查询
        val = conds.get("isNullProps");
        if (null != val) {
            String[] isNullProps = ((String) val).split(",");
            for (String prop : isNullProps) {
                String colName = fieldsMap.get(prop);
                if (colName != null) {
                    queryWrapper.isNull(colName);
                }
            }
        }
        val = conds.get("isNotNullProps");
        if (null != val) {
            String[] isNotNullProps = ((String) val).split(",");
            for (String prop : isNotNullProps) {
                String colName = fieldsMap.get(prop);
                if (colName != null) {
                    queryWrapper.isNotNull(colName);
                }
            }
        }

        List<Field> fields = getPersistentFields();
        for (Field f : fields) {
            DsField tableField = f.getAnnotation(DsField.class);
            
            //ApiModelProperty ap = f.getAnnotation(ApiModelProperty.class);
            if (tableField != null) {
                Class<?> t = f.getType();
                //String fieldNameCapital = StringUtils.capitalize(f.getName());

                //处理精确查询
                String colName = fieldsMap.get(f.getName());
                if (colName != null) {
                    val = conds.get(f.getName());
                    if (null != val) {
                        if (t == String.class && StringUtils.isNotBlank((String) val)) {
                            queryWrapper.eq(colName, val);
                        } else {
                            queryWrapper.eq(colName, val);
                        }
                         continue;
                    } 
                }else {
                    System.out.println(getPersistentClass() + "类属性没有注解对应的数据库表字段名：" + f.getName());
                    continue;
                }
                //处理不等于查询
                String colNameNotEq = f.getName() + "_NotEq";
                val = conds.get(colNameNotEq);
                if (null != val) {
                    queryWrapper.ne(colName, val);
                    continue;
                }
                if (t == String.class) {
                    //处理like查询
                    String colNameLike = f.getName() + "_Like";
                    val = conds.get(colNameLike);
                    if (StringUtils.isNotBlank((String) val)) {
                        queryWrapper.like(colName, val);
                    }
                    String colNameLikeLeft = f.getName() + "_LikeLeft";
                    val = conds.get(colNameLikeLeft);
                    //System.out.println("colNameLikeLeft:"+val);
                    if (StringUtils.isNotBlank((String) val)) {
                        //System.out.println("StringUtils.isNotBlank:"+val);
                        queryWrapper.likeLeft(colName, val);
                    }
                    String colNameLikeRight = f.getName() + "_LikeRight";
                    val = conds.get(colNameLikeRight);
                    if (StringUtils.isNotBlank((String) val)) {
                        queryWrapper.likeRight(colName, val);
                    }
                }

                //处理in查询
                String colNameIn = f.getName() + "_In";
                val = conds.get(colNameIn);
                if (null != val) {
                    if (val instanceof String) {
                        if (t == Long.class) {
                            queryWrapper.in(colName, MyDsDatabaseUtil.toLongList((String) val));
                        } else {
                            String[] array = ((String) val).split(",");
                            queryWrapper.in(colName, Arrays.asList(array));
                        }
                    } else if (val instanceof Collection) {
                        queryWrapper.in(colName, (Collection) val);
                    }
                }
                //处理not in查询
                String colNameNotIn = f.getName() + "_NotIn";
                val = conds.get(colNameNotIn);
                if (null != val) {
                    if (val instanceof String) {
                        if (t == Long.class) {
                            queryWrapper.notIn(colName, MyDsDatabaseUtil.toLongList((String) val));
                        } else {
                            String[] array = ((String) val).split(",");
                            queryWrapper.notIn(colName, Arrays.asList(array));
                        }
                    } else if (val instanceof Collection) {
                        queryWrapper.notIn(colName, (Collection) val);
                    }
                }
                

                if (t == Date.class || t == LocalDate.class || t == LocalDateTime.class) {
                    //处理范围查询
                    String colNameStart = f.getName() + "_Start";
                    val = conds.get(colNameStart);
                    if (null != val) {
                        queryWrapper.ge(colName, DateUtil.convert(val));
                    }
                    String colNameEnd = f.getName() + "_End";
                    val = conds.get(colNameEnd);
                    if (null != val) {
                        queryWrapper.le(colName, DateUtil.convert(val));
                    }
                    //处理时间模糊搜索，按照格式'yyyyMMddhhmmssSSS'顺序匹配，例如输入4位数字，将搜索年份；输入6位数字，将搜索年份+月份，依次类推。
                    String colNameFuzzy = f.getName() + "_Fuzzy";
                    val = conds.get(colNameFuzzy);
                    if (null != val && val instanceof String) {
                        Date startTime = DateUtil.getMinTimeByFuzzyDateString((String) val);
                        Date endTime = DateUtil.getMinTimeByFuzzyDateString((String) val);
                        queryWrapper.ge(colName, startTime);
                        queryWrapper.le(colName, endTime);
                    }
                    continue;
                }
                //if (t == Long.class || t == Integer.class || t == Float.class|| t == Double.class|| t == BigDecimal.class) {
                    //处理>/<
                    String colNameGt = f.getName() + "_Gt";
                    val = conds.get(colNameGt);
                    if (null != val) {
                        queryWrapper.gt(colName, MyDsDatabaseUtil.convert(val,t));
                    }
                    String colNameLt = f.getName() + "_Lt";
                    val = conds.get(colNameLt);
                    if (null != val) {
                        queryWrapper.lt(colName, MyDsDatabaseUtil.convert(val,t));
                    }
                    //处理>/<
                    String colNameGe = f.getName() + "_Ge";
                    val = conds.get(colNameGe);
                    if (null != val) {
                        queryWrapper.ge(colName, MyDsDatabaseUtil.convert(val,t));
                    }
                    String colNameLe = f.getName() + "_Le";
                    val = conds.get(colNameLe);
                    if (null != val) {
                        queryWrapper.le(colName, MyDsDatabaseUtil.convert(val,t));
                    }
                //}
            }
        }

        return queryWrapper;
    }

    /**
     * 查找对象集合
     *
     * @param params
     * @param orderss
     * @return 查找到的结果List
     */
     public List<T> findList(Map<String, Object> params, OrderByProp... orders) {
      //TODO
        return null;
    }

    /**
     * 获得一个对象
     *
     * @param conds
     * @return Object
     */
     public T get(Map<String, Object> conds) {
        try {
            QueryWrapper<T> queryWrapper = buildQueryWrapper(conds);
            return (T) getOne(queryWrapper);
//        //处理排序
//        Page p = new Page(1, 1);
////        if (sort != null) {
////            OrderItem o = new OrderItem(sort, "asc".equalsIgnoreCase(order) ? true : false);
////            p.addOrder(o);
////        }
//        IPage page = getCurrentService().page(p,queryWrapper);
//        if(!page.getRecords().isEmpty()){
//            return (T) page.getRecords().get(0);
//        }
        } catch (NoSuchFieldException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * select count(*) from 类
     *
     * @param conds
     * @return int
     */
     public int count(Map<String, Object> conds) {
try {
        QueryWrapper<T> queryWrapper = buildQueryWrapper(conds);
        return count(queryWrapper);
        } catch (NoSuchFieldException ex) {
            throw new RuntimeException(ex);
        }
    }

//    /**
//     * 执行SQL
//     *
//     * @param sql sql
//     * @param param 参数
//     * @return int 被更新的行数
//     */
//    public Integer executeSqlByListParams(String sql, List<Object> param);
//
//    public Long countBySql(String sql, Object... param);
//    /**
//     * 执行SQL
//     *
//     * @param sql sql
//     * @param param 参数
//     * @return int 被更新的行数
//     */
//    public Integer executeSql(String sql, Object... param);
    /**
     * 查找对象集合-指定范围（闭区间）
     *
     * @author karl date 2020 05 12 create
     * @param start 起始位 start和end都为0，查询全部
     * @param end 终止位
     * @param params 参数
     * @param orderss
     * @return 分页后的List
     */
     List<T> findRangeByMap(int start, int end, Map<String, Object> params, OrderByProp... orders) {
       //TODO
        return null;
    }
    
    /**
     * 查找对象集合-指定范围（闭区间）
     *
     * @author karl date 2020 05 12 create
     * @param start 起始位 start和end都为0，查询全部
     * @param end 终止位
     * @param params 参数
     * @param orderss 排序-null不排序,基于属性列举的排序字符串,例如 p1 asc,p2 desc...
     * @return 分页后的List
     */
     List<T> findRangeByMapWithOrders(int start, int end, Map<String, Object> params, String orders) {
       //TODO
        return null;
    }

//    /**
//     * 查找对象集合
//     *
//     * @param hql hql
//     * @param param 参数
//     * @return 查找到的结果List
//     */
//    public List<T> findByListParams(String hql, List<Object> param);
//    /**
//     * 查找对象集合,带分页
//     *
//     * @param hql hql
//     * @param page 当前页
//     * @param rows 每页显示记录数
//     * @param param 参数
//     * @return 分页后的List
//     */
//    public List<T> findRangeByListParams(String hql, int page, int rows, List<Object> param);
//    /**
//     * 查找对象集合,带分页
//     *
//     * @param hql hql
//     * @param page 当前页
//     * @param rows 每页显示记录数
//     * @param param 参数
//     * @return 分页后的List
//     */
//    public List findRange(String hql, int page, int rows, Object... param);
    /**
     * 以给定样本对象不为null的属性构造查询条件，返回实体对象集合-指定范围（闭区间）
     *
     * @ param start起始位 start和end都为0，查询全部
     * @param end 终止位
     * @param example
     * @param orderss
     * @return
     */
     public List<T> findRangeByExample(int start, int end, T example, OrderByProp... orders) {
       //TODO
        return null;
    }

    /**
     * 实体指定属性list查询(多数情形下可提高性能) 以给定样本对象的指定属性名称集构造精确匹配查询条件(包括null值)，返回（排序）指定属性集合
     *
     * @param selectProp 指定属性名称
     * @param orderss 排序
     * @param params 属性名称-值 参数集
     * @return
     */
     public List<Object> findPropListByMap(String selectProp, Map<String, Object> params, OrderByProp... orders) {
        return findPropRangeByMap(0, 0, selectProp, params, orders);
    }

    /**
     * 实体指定属性list查询(多数情形下可提高性能) 以给定样本对象的指定属性名称集构造精确匹配查询条件(包括null值)，返回（排序）指定属性集合
     *
     * @param selectProp 指定属性名称
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public List<Object> findPropListByProps(String selectProp, String searchProps, Object... propValues) {
        //TODO
        return null;
    }

    /**
     * 实体指定属性-分页查询(多数情形下可提高性能) 以给定样本对象的指定属性名称集构造精确匹配查询条件(包括null值)，返回（排序）指定属性集合
     *
     * @param selectProp 指定属性名称
     * @param orderss 排序
     * @param start 起始位 start和end都为0，查询全部
     * @param end 终止位
     * @param params 属性名称-值 参数集
     * @return
     */
     public List<Object> findPropRangeByMap(int start, int end, String selectProp, Map<String, Object> params, OrderByProp... orders) {
       //TODO
        return null;
    }

    /**
     * 实体对象（部分属性）-分页查询(多数情形下可提高性能)
     * 以给定样本对象的指定属性名称集构造精确匹配查询条件(包括null值)，返回（排序）实体对象(封装少量列举的属性)集合
     *
     * @param selectProps 以逗号分隔的属性名称列举
     * @param orderss 排序
     * @param start 起始位 start和end都为0，查询全部
     * @param end 终止位
     * @param params 属性名称-值 参数集
     * @return
     */
     public List<T> findPropsRangeByMap(int start, int end, String selectProps, Map<String, Object> params, OrderByProp... orders) {
        //TODO
        return null;
    }
    
        /**
     * 实体对象（部分属性）-分页查询(多数情形下可提高性能)
     * 以给定样本对象的指定属性名称集构造精确匹配查询条件(包括null值)，返回（排序）实体对象(封装少量列举的属性)集合
     *
     * @param selectProps 以逗号分隔的属性名称列举
     * @param orderss 排序-null不排序,基于属性列举的排序字符串,例如 p1 asc,p2 desc...
     * @param start 起始位 start和end都为0，查询全部
     * @param end 终止位
     * @param params 属性名称-值 参数集
     * @return
     */
     public List<T> findPropsRangeByMapWithOrders(int start, int end, String selectProps, Map<String, Object> params, String orders) {
        //TODO
        return null;
    }

//    /**
//     * 查找sql字段集合(分页)
//     *
//     * @param sql
//     * @param page
//     * @param rows
//     * @param param
//     * @return
//     */
//    public List findRangeBySql(String sql, int page, int rows, Object... param);
//    /**
//     * 以给定样本对象的指定属性名称集构造精确匹配查询条件(包括null值)，返回（排序）实体对象指定属性分页列表
//     *
//     * @param selectProp 指定返回属性
//     * @param page page和rows都为0，查询全部
//     * @param rows
//     * @param ordersBy 排序
//     * @param params 属性名称-值 参数集
//     * @return
//     */
//    public List findPropPageByMap(String selectProp, int page, int rows, Map<String, Object> params, OrderByProp... orderBy);
//    /**
//     * 查找对象集合
//     *
//     * @author karl date 2020 05 12 create
//     * @param hql hql
//     * @param param 参数
//     * @return 分页后的List
//     */
//    public List findList(String hql, Object... param);
//	
//    /**
//     * 查找sql字段集合
//     *
//     * @author karl date 2016 01 10 create
//     * @param sql sql
//     * @param param 参数
//     * @return 分页后的List
//     */
//    public List findListBySql(String sql, Object... param);
//    /**
//     * 查找对象集合
//     *
//     * @param hql hql
//     * @param param 参数
//     * @return 查找到的结果List
//     */
//    public List findListByListParams(String hql, List<?> param);
    /**
     * 软删除
     *
     * @author karl date 2020 06 17 create
     * @param o
     */
     public void markDelete(T o) throws Exception {
        //TODO
        
    }

   
//
//    /**
//     * 根据给定实体类clazz及ids列表，查询对应实体对象集合
//     *
//     * @param clazz
//     * @param ids
//     * @return
//     */
//    public List findEntityByIds(Class clazz, List<PK> ids);

    /**
     * 更新实体对象o的指定属性propertyNames
     *
     * @param o
     * @param propNames
     */
     public void updateProperties(T o, String... propNames) throws Exception {
//TODO
       
    }

//    /**
//     * 批量保存所有非静态、非临时（Transient）、非关联（一对多，多对一，多对多等）的field（包括父类）
//     *
//     * @param objects
//     */
//    public void batchUpdate(List objects);
//    /**
//     * 批量保存指定属性propertyNames
//     *
//     * @param objects
//     * @param propertyNames
//     */
//    public void batchUpdate(List objects, String... propertyNames);
//
//    /**
//     * 批量创建实体对象
//     *
//     * @param objects
//     */
//    public void batchInsert(List objects);
    /**
     * 以给定样本对象不为null的属性构造查询条件，返回实体对象集合
     *
     * @param example
     * @param orderss
     * @return
     */
     public List<T> findByExample(T example, OrderByProp... orders) {
       //TODO
        return null;
    }

    /**
     * 以给定样本对象不为null的属性构造查询条件，返回首个实体对象
     *
     * @param example
     * @param orderss
     * @return
     */
     public T getByExample(T example, OrderByProp... orders) {
       //TODO
        return null;
    }

    /**
     * 以给定样本对象的指定属性名称集构造精确匹配查询条件(包括null值)，返回（排序）首个实体对象
     *
     * @param orderss 排序
     * @param params 属性名称-值 参数集
     * @return
     */
     public T getByMap(Map<String, Object> params, OrderByProp... orders) {
       //TODO
        return null;
    }

    /**
     * 以指定属性名称集构造精确匹配查询条件(包括null值)，返回（排序）首个实体对象指定属性值
     *
     * @param propName 指定返回属性
     * @param orderss 排序
     * @param params 属性名称-值 参数集
     * @return
     */
     public Object getPropByMap(String propName, Map<String, Object> params, OrderByProp... orders) throws Exception {
       //TODO
        return null;
    }

    /**
     * 根据指定一个或多个属性名、值的筛选条件，精确查询一个对象的属性值
     *
     * @param propName 属性名
     * @param orderss 排序-null不排序
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public Object getPropByProps(String propName, OrderByProp[] orders, String searchProps, Object... propValues) {
       //TODO
        return null;
    }

    /**
     * 参数Map封装辅助生成
     *
     * @return
     */
     public Map<String, Object> newParameters() {
        return new HashMap();
    }

//    /**
//     * 根据参数获取指定属性
//     *
//     * @param hql
//     * @param param
//     * @return
//     */
//    public Object getProperty(String hql, Object... param);
//    /**
//     * 根据一个或多个参数查询分页对象集合
//     *
//     * @param hql
//     * @param page
//     * @param rows
//     * @param param
//     * @return
//     */
//    public List findRangeByListParams(String hql, int page, int rows, Object... param);
//    /**
//     * 获取当前DAO缓存空间名称
//     *
//     * @return
//     */
//    public String getCacheRegion();
//
//    /**
//     * 清理当前DAO缓存空间
//     */
//    public void clearCache();
//    /**
//     * 在当前DAO缓存空间设置缓存(hql查询数据)
//     *
//     * @param key
//     * @param value
//     */
//    public void setHqlCache(String hql, String key, Object value);
//
//    /**
//     * 在当前DAO缓存空间设置缓存(调用底层sql查询数据)
//     *
//     * @param key
//     * @param value
//     */
//    public void setSqlCache(String sql,String key, Object value);
//
//    /**
//     * 把缓存key加入对应DAO缓存空间以便数据更改时清理缓存
//     *
//     * @param key
//     */
//    public void addCachedKey(String key);
//
//    /**
//     * 在当前DAO缓存空间获取缓存，无责返回null
//     *
//     * @param <E>
//     * @param clazz
//     * @param key
//     * @return
//     */
//    public <E> E getCache(Class<E> clazz, String key);
    /**
     * 根据指定属性名、值获取一个实体对象
     *
     * @param propName
     * @param propValue
     * @param orderss
     * @return
     */
     public T findOneByProp(String propName, Object propValue, OrderByProp... orders) {
        //TODO
        return null;
    }
    
    

    /**
     * 根据指定一个或多个属性名、值的筛选条件获取一个实体对象
     *
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public T findOneByProps(String searchProps, Object... propValues) {
       //TODO
        return null;
    }

    /**
     * 根据指定一个或多个属性名、值的筛选条件获取一个实体对象
     *
     * @param selectProps 指定返回属性--以逗号分隔的属性名称列举
     * @param searchProps 筛选条件--以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public T findOneByProps(String selectProps, String searchProps, Object... propValues) {
        //TODO
        return null;
    }
    
    /**
     * 根据指定一个或多个属性名、值的筛选条件获取一个实体对象
     *
     * @param selectProps 指定返回属性--以逗号分隔的属性名称列举
     * @param searchProps 筛选条件--以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public T findOneByPropsWithOrders(String orders,String selectProps, String searchProps, Object... propValues) {
        //TODO
        return null;
    }
    
   

    /**
     * 根据指定一个或多个属性名、值的筛选条件获取一个实体对象
     *
     * @param orderss
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public T findOneByProps(OrderByProp orders, String searchProps, Object... propValues) {
       //TODO
        return null;
    }

    /**
     * 根据指定一个或多个属性名、值的筛选条件获取实体对象集合
     *
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public List<T> findListByProps(String searchProps, Object... propValues) {
        return findRangeByProps(null, 0, 0, searchProps, propValues);
    }

    /**
     * 根据指定一个或多个属性名、值的筛选条件获取实体对象集合
     *
     * @param selectProps 指定返回属性--以逗号分隔的属性名称列举
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public List<T> findListByProps(String selectProps, String searchProps, Object... propValues) {
        return findRangeByProps(null, 0, 0, selectProps, searchProps, propValues);
    }
    
    /**
     * 根据指定一个或多个属性名、值的筛选条件获取实体对象集合
     *
     * @param orderss 排序-null不排序,基于属性列举的排序字符串,例如 p1 asc,p2 desc...
     * @param selectProps 指定返回属性--以逗号分隔的属性名称列举
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public List<T> findListByPropsWithOrders(String orders,String selectProps, String searchProps, Object... propValues) {
       //TODO
        return null;
    }

    /**
     * 根据指定一个或多个属性名、值的筛选条件获取实体对象集合
     *
     * @param orders
     * @param selectProps 指定返回属性--以逗号分隔的属性名称列举
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public List<T> findListByProps(OrderByProp[] orders, String selectProps, String searchProps, Object... propValues) {
        return findRangeByProps(orders, 0, 0, selectProps, searchProps, propValues);
    }

    /**
     * 根据指定一个或多个属性名、值的筛选条件获取指定范围（闭区间）实体对象集合
     *
     * @param orders 排序-null不排序
     * @param start 起始位 start和end都为0，查询全部
     * @param end 终止位
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public List<T> findRangeByProps(OrderByProp[] orders, int start, int end, String searchProps, Object... propValues) {
        ensureInit();
        QueryWrapper<T> wrapper = buildQueryWrapper(orders, searchProps, propValues);
        return sliceEntities(wrapper, start, end);
    }
    
        /**
     * 根据指定一个或多个属性名、值的筛选条件获取指定范围（闭区间）实体对象集合
     *
     * @param orderss 排序-null不排序,基于属性列举的排序字符串,例如 p1 asc,p2 desc...
     * @param start 起始位 start和end都为0，查询全部
     * @param end 终止位
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public List<T> findRangeByPropsWithOrders(String orders, int start, int end, String searchProps, Object... propValues) {
        ensureInit();
        OrderWrapper ow = parseOrders(orders);
        QueryWrapper<T> wrapper = buildQueryWrapper(searchProps, propValues);
        applyOrderWrapper(wrapper, ow);
        return sliceEntities(wrapper, start, end);
    }

    /**
     * 根据指定一个或多个属性名、值的筛选条件获取指定范围（闭区间）实体对象集合
     *
     * @param orders 排序-null不排序
     * @param start 起始位 start和end都为0，查询全部
     * @param end 终止位
     * @param selectProps 指定返回属性--以逗号分隔的属性名称列举
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public List<T> findRangeByProps(OrderByProp[] orders, int start, int end, String selectProps, String searchProps, Object... propValues) {
        ensureInit();
        QueryWrapper<T> wrapper = buildQueryWrapper(orders, selectProps, searchProps, propValues);
        return sliceEntities(wrapper, start, end);
    }

    /**
     * 根据指定一个或多个属性名、值删除对象集合
     *
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public int deleteByProps(String searchProps, Object... propValues) {
        ensureInit();
        QueryWrapper<T> wrapper = buildQueryWrapper(searchProps, propValues);
        List<T> list = listEntities(wrapper);
        int deleted = 0;
        for (T e : list) {
            deleted += removeById(e.getId());
        }
        return deleted;
    }

    /**
     * 根据指定一个或多个属性名、值条件更新数据表
     *
     * @param o 不为null的字段将更新到数据库
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public int updateByProps(T o, String searchProps, Object... propValues) {
        ensureInit();
        if (o == null) {
            return 0;
        }
        QueryWrapper<T> wrapper = buildQueryWrapper(searchProps, propValues);
        List<T> list = listEntities(wrapper);
        int updated = 0;
        for (T e : list) {
            MyBeanUtils.copyBeanNotNull2Bean(o, e);
            updated += updateById(e);
        }
        return updated;
    }

    /**
     * 生成查询条件
     *
     * @param orders 排序-null不排序
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public QueryWrapper buildQueryWrapper(OrderByProp[] orders, String searchProps, Object... propValues) {
        ensureInit();
        QueryWrapper<T> wrapper = buildQueryWrapper(searchProps, propValues);
        applyOrders(wrapper, orders);
        return wrapper;
    }

    /**
     * 生成查询条件
     *
     * @param orderss 排序-null不排序
     * @param selectProps 以逗号分隔的属性名称列举(定制查询字段,按需查询以提高性能)
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public QueryWrapper buildQueryWrapper(OrderByProp[] orders, String selectProps, String searchProps, Object... propValues) {
        ensureInit();
        QueryWrapper<T> wrapper = buildQueryWrapper(searchProps, propValues);
        if (selectProps != null && !selectProps.isBlank()) {
            wrapper.select(toColumns(selectProps));
        }
        applyOrders(wrapper, orders);
        return wrapper;
    }

    /**
     * 生成更新查询条件
     *
     * @param searchProps 以逗号分隔的属性名称列举
     * @param propValues 可变长度属性值列举(null转换为 is null)
     * @return
     */
     public UpdateWrapper buildUpdateWrapper(String searchProps, Object... propValues) {
        ensureInit();
        return new UpdateWrapper(buildQueryWrapper(searchProps, propValues));
    }

    /**
     * 根据筛选条件更新实体对象指定字段
     *
     * @param updateProps 以逗号分隔的属性名称列举-更新字段
     * @param updateValues 可变长度属性值列举(null则set null)
     * @param searchProps 以逗号分隔的属性名称列举-更新条件
     * @param propValues 可变长度属性值列举(null转换为 is null)
     */
     void updateProps(String updateProps, Object[] updateValues, String searchProps, Object... propValues) {
//        UpdateWrapper wrapper = buildUpdateWrapper(searchProps, propValues);
//        String[] props = updateProps.split(",");
//        if (props.length != updateValues.length) {
//            throw new RuntimeException("paramter [updateValues] length not valid,expected:" + props.length);
//        }
//        Map<String, String> fieldsMap = getField2ColMap();
//        int i = 0;
//        for (String prop : props) {
//            String col = fieldsMap.get(prop);
//            if (col != null) {
//                wrapper.set(col, updateValues[i]);
//            } else {
//                throw new RuntimeException(getPersistentClass() + " entity propety orm map not exists:" + prop);
//            }
//            i++;
//        }
//        getCurrentService().update(wrapper);
    }

    /**
     * 通用操作-设置实体对象状态（必须存在status字段）
     *
     * @param ids ID集合
     * @param status 状态值
     */
     void resetStatusByIds(List<Long> ids, Short status) {
        updateProps("status", new Object[]{status}, "id", ids);
    }

     void batchUpdate(List objects) {
       
        //TODO
    }

     void batchInsert(List objects) {
       //TODO
    }
    
     void batchInsert(List objects, String propNames) {
        batchInsert(objects,propNames.split(","));
    }
    
     void batchInsert(List objects, String... propNames) {
       //TODO
    }

     void batchUpdate(List objects, String... propNames) {
        //TODO
     }
    
     void batchMerge(List objects, String... propNames) {
        if (objects.isEmpty()) {
            return;
        }
        List inserts = new ArrayList();
        List updates = new ArrayList();
        for(Object o : objects){
            if(existsById(o)){
                updates.add(o);
            }else{
                inserts.add(o);
            }
        }
         batchInsert(inserts, propNames);
         batchUpdate(updates, propNames);
    }
    
     void batchDelete(List objects) {
        //TODO
    }

   
    /**
     * 功能描述：获取实体Insertable属性值列表
     *
     * @param clazz
     * @param entity 实体类对象
     * @return
     */
     List<Object> getInsertablePropValues(Class clazz, Object entity) {
        ensureInit();
        if (entity == null) {
            return new ArrayList<>();
        }
        List<Object> out = new ArrayList<>();
        for (Field f : getPersistentFields()) {
            try {
                out.add(f.get(entity));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return out;
    }

    /**
     * 功能描述：获取实体Insertable字段名称列表
     *
     * @param clazz 实体类
     * @return
     */
     List<String> getInsertableColumnNames(Class<?> clazz) {
        ensureInit();
        return new ArrayList<>(getField2ColMap().values());
    }
    
    

    /**
     * 功能描述：获取实体Updateable属性值列表
     *
     * @param clazz 实体类
     * @param entity 实体类对象
     * @return
     */
     List<Object> getUpdateablePropValues(Class clazz, Object entity) {
//        Map<String, String> map = SERVICE_MAP.get(clazz).getField2ColMap();
//        List<Object> list = new ArrayList();
//        List<String> ids = getIdColumnNames();
//        for (String s : map.values()) {
//            if (!ids.contains(s)) {
//                list.add(map.get(s));
//            }
//        }
//        return list;

        ensureInit();
        return getInsertablePropValues(clazz, entity);
    }

    /**
     * 功能描述：获取实体Updateable字段名称列表
     *
     * @param clazz 实体类
     * @return
     */
     List<String> getUpdateableColumnNames(Class<?> clazz) {
//        Map<String, String> map = SERVICE_MAP.get(clazz).getField2ColMap();
//        List<String> list = new ArrayList();
//        List<String> ids = getIdColumnNames(clazz);
//        for (String s : map.values()) {
//            if (!ids.contains(s)) {
//                list.add(map.get(s));
//            }
//        }
//        return list;
        ensureInit();
        return getInsertableColumnNames(clazz);
    }

    /**
     * 功能描述：获取实体Updateable属性值列表(指定属性集)
     *
     * @param entity 实体类对象
     * @param props
     * @return
     */
     List<Object> getPropValues(Class clazz, Object entity, String... props) {
//        Map<String, Field> map = SERVICE_MAP.get(clazz).getFieldsMap();
//        List<Object> list = new ArrayList();
//        for (String s : props) {
//            try {
//                list.add(map.get(s).get(entity));
//            } catch (Exception ex) {
//                System.out.println("s->"+s);
//                throw new RuntimeException(ex);
//            }
//        }
//        return list;
        ensureInit();
        if (entity == null || props == null || props.length == 0) {
            return new ArrayList<>();
        }
        Map<String, Field> map = fieldMapOf(clazz);
        List<Object> out = new ArrayList<>(props.length);
        for (String p : props) {
            Field f = map.get(p);
            if (f == null) {
                out.add(null);
                continue;
            }
            try {
                out.add(f.get(entity));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return out;
    }

    /**
     * 功能描述：获取实体Updateable字段名称列表(指定属性集)
     *
     * @param clazz 实体类
     * @param props
     * @return
     */
     List<String> getColumnNames(Class<?> clazz, String... props) {
//        Map<String, String> map = SERVICE_MAP.get(clazz).getField2ColMap();
//        List<String> list = new ArrayList();
//        for (String s : props) {
//            list.add(map.get(s));
//        }
//        return list;
        ensureInit();
        if (props == null || props.length == 0) {
            return new ArrayList<>();
        }
        Map<String, String> m = field2ColOf(clazz);
        List<String> out = new ArrayList<>(props.length);
        for (String p : props) {
            out.add(m.get(p));
        }
        return out;
    }

    /**
     * 功能描述：通过实体类和属性，获取实体类属性对应的表字段名称
     *
     * @param clazz 实体类
     * @param prop 属性名称
     * @return 字段名称
     */
     String getColumnName(Class<?> clazz, String prop) {
//        Map<String, String> map = SERVICE_MAP.get(clazz).getField2ColMap();
//        return map.get(prop);
        ensureInit();
        return field2ColOf(clazz).get(prop);
    }

     String getColumnName(String prop) {
        ensureInit();
        return getField2ColMap().get(prop);
    }

     Object getPropValue(Object entity, String prop) {
        ensureInit();
        if (entity == null || prop == null || prop.isBlank()) {
            return null;
        }
        Field f = getFieldsMap().get(prop);
        if (f == null) {
            return null;
        }
        try {
            return f.get(entity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, String> getField2ColMap() {
        ensureInit();
        return field2ColMap;
    }

    private List<Field> getPersistentFields() {
        ensureInit();
        return persistentFields;
    }

    private int countByWrapper(QueryWrapper<T> queryWrapper) {
        ensureInit();
        if (isEmptyWrapper(queryWrapper)) {
            return idSet.size();
        }
        int c = 0;
        for (Long id : candidateIdsForQuery(queryWrapper)) {
            T e = db.getTable(persistentClass, id, readTimestamp);
            if (matchesAll(queryWrapper, e)) {
                c++;
            }
        }
        return c;
    }

    private boolean isEmptyWrapper(QueryWrapper<T> wrapper) {
        return wrapper == null
                || (wrapper.criteria().isEmpty() && wrapper.orBranches().isEmpty());
    }

    private T getOneByWrapper(QueryWrapper<T> queryWrapper) {
        ensureInit();
        for (Long id : candidateIdsForQuery(queryWrapper)) {
            T e = db.getTable(persistentClass, id, readTimestamp);
            if (matchesAll(queryWrapper, e)) {
                return e;
            }
        }
        return null;
    }

    public Map<String, Field> getFieldsMap() {
        ensureInit();
        return fieldsMap;
    }

    public List<Object> findListByProps(OrderWrapper ow, String selectProps, String joinToProp, Object val) {
        ensureInit();
        QueryWrapper<T> wrapper = new QueryWrapper<>();
        if (selectProps != null && !selectProps.isBlank()) {
            wrapper.select(toColumns(selectProps));
        }
        if (joinToProp != null && !joinToProp.isBlank()) {
            String col = getColumnName(joinToProp.trim());
            if (col != null) {
                if (val == null) {
                    wrapper.isNull(col);
                } else {
                    wrapper.eq(col, val);
                }
            }
        }
        applyOrderWrapper(wrapper, ow);
        return list(wrapper);
    }

    public List<Object> list(QueryWrapper wrapper) {
        if (isDistributed()) {
            try {
                @SuppressWarnings("unchecked")
                QueryWrapper<T> w = (QueryWrapper<T>) wrapper;
                List<Long> ids = distClient.queryIds(persistentClass, w, 0, Integer.MAX_VALUE);
                List<T> entities = new ArrayList<>(ids.size());
                for (Long id : ids) {
                    T e = distClient.getTable(persistentClass, id, false);
                    if (e != null) entities.add(e);
                }
                if (w.selectCols().isEmpty()) {
                    return new ArrayList<>(entities);
                }
                return project(entities, w.selectCols());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ensureInit();
        @SuppressWarnings("unchecked")
        QueryWrapper<T> w = (QueryWrapper<T>) wrapper;
        List<T> entities = listEntities(w);
        if (w.selectCols().isEmpty()) {
            return new ArrayList<>(entities);
        }
        return project(entities, w.selectCols());
    }

    public List<T> findRangeByWrapper(QueryWrapper wrapper, int start, int end) {
        if (isDistributed()) {
            try {
                @SuppressWarnings("unchecked")
                QueryWrapper<T> w = (QueryWrapper<T>) wrapper;
                List<Long> ids = distClient.queryIds(persistentClass, w, start, end);
                List<T> entities = new ArrayList<>(ids.size());
                for (Long id : ids) {
                    T e = distClient.getTable(persistentClass, id, false);
                    if (e != null) entities.add(e);
                }
                return entities;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ensureInit();
        @SuppressWarnings("unchecked")
        QueryWrapper<T> w = (QueryWrapper<T>) wrapper;
        return sliceEntities(w, start, end);
    }

    public QueryWrapper<T> buildQueryWrapper() {
        ensureInit();
        return new QueryWrapper<>();
    }

    @SuppressWarnings("unchecked")
    public List<T> findList(QueryWrapper wrapper) {
        if (isDistributed()) {
            try {
                QueryWrapper<T> w = (QueryWrapper<T>) wrapper;
                List<Long> ids = distClient.queryIds(persistentClass, w, 0, Integer.MAX_VALUE);
                List<T> entities = new ArrayList<>(ids.size());
                for (Long id : ids) {
                    T e = distClient.getTable(persistentClass, id, false);
                    if (e != null) entities.add(e);
                }
                return entities;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ensureInit();
        QueryWrapper<T> w = (QueryWrapper<T>) wrapper;
        return listEntities(w);
    }

    @SuppressWarnings("unchecked")
    public T getOne(QueryWrapper wrapper) {
        if (isDistributed()) {
            try {
                QueryWrapper<T> w = (QueryWrapper<T>) wrapper;
                List<Long> ids = distClient.queryIds(persistentClass, w, 0, 1);
                if (ids.isEmpty()) return null;
                return distClient.getTable(persistentClass, ids.get(0), false);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ensureInit();
        QueryWrapper<T> w = (QueryWrapper<T>) wrapper;
        return getOneByWrapper(w);
    }

    @SuppressWarnings("unchecked")
    public int count(QueryWrapper wrapper) {
        if (isDistributed()) {
            try {
                QueryWrapper<T> w = (QueryWrapper<T>) wrapper;
                return distClient.queryIds(persistentClass, w, 0, Integer.MAX_VALUE).size();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        ensureInit();
        QueryWrapper<T> w = (QueryWrapper<T>) wrapper;
        return countByWrapper(w);
    }

    public QueryWrapper buildQueryWrapper(OrderWrapper ow, String props, String string) {
        ensureInit();
        QueryWrapper<T> w = new QueryWrapper<>();
        if (props != null && !props.isBlank()) {
            w.select(toColumns(props));
        }
        applyOrderWrapper(w, ow);
        return w;
    }

    public List<T> findListByMap(Map<String, ?> conds) {
        ensureInit();
        try {
            QueryWrapper<T> w = buildQueryWrapper(conds);
            return listEntities(w);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private QueryWrapper<T> buildQueryWrapper(String searchProps, Object... propValues) {
        QueryWrapper<T> w = new QueryWrapper<>();
        if (searchProps == null || searchProps.isBlank()) {
            return w;
        }
        String[] props = searchProps.split(",");
        if (propValues == null) {
            propValues = new Object[0];
        }
        if (props.length != propValues.length) {
            throw new IllegalArgumentException("propValues length mismatch: expected=" + props.length + ", actual=" + propValues.length);
        }
        for (int i = 0; i < props.length; i++) {
            String prop = props[i].trim();
            if (prop.isEmpty()) {
                continue;
            }
            String col = getColumnName(prop);
            if (col == null) {
                continue;
            }
            Object v = propValues[i];
            if (v == null) {
                w.isNull(col);
            } else if (v instanceof Collection<?> colVals) {
                w.in(col, colVals);
            } else {
                w.eq(col, v);
            }
        }
        return w;
    }

    private String toColumns(String props) {
        String[] parts = props.split(",");
        StringBuilder sb = new StringBuilder(props.length() + 8);
        boolean first = true;
        for (String p : parts) {
            String prop = p.trim();
            if (prop.isEmpty()) {
                continue;
            }
            String col = getColumnName(prop);
            if (col == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            sb.append(col);
            first = false;
        }
        return sb.toString();
    }

    private DsEqIndexStore openIndexStore(IndexedColInfo col) throws IOException {
        String safeSpace = DsPathUtil.toSafeFileName(persistentClass.getName(), 80);
        String safeCol = DsPathUtil.toSafeFileName(col.columnName, 64);
        String space = "indexes/" + safeSpace + "/" + schemaId;
        String indexName = "eq_" + safeCol;
        return new DsEqIndexStore(db.getRoot(), space, indexName, col.valueKind);
    }

    private DsEqIndexStore openCompositeIndexStore(CompositeIndexInfo ci) throws IOException {
        String safeSpace = DsPathUtil.toSafeFileName(persistentClass.getName(), 80);
        String safeName = DsPathUtil.toSafeFileName(ci.name, 64);
        String space = "indexes/" + safeSpace + "/" + schemaId;
        String indexName = "cidx_" + safeName;
        return new DsEqIndexStore(db.getRoot(), space, indexName, DsEqIndexStore.IndexedValueKind.LONG);
    }

    private long toIndexLongValue(Object val, IndexedColInfo col) {
        if (val == null) return 0L;
        Class<?> ft = col.fieldType;
        Object v = coerce(val, ft);
        if (v == null) return 0L;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Short s) return s.longValue();
        if (v instanceof Byte b) return b.longValue();
        if (v instanceof Boolean bo) return bo ? 1L : 0L;
        if (v instanceof Date d) return d.getTime();
        if (v instanceof LocalDate ld) return ld.toEpochDay();
        if (v instanceof LocalDateTime ldt) return ldt.toEpochSecond(java.time.ZoneOffset.UTC);
        return 0L;
    }

    private String toIndexStringValue(Object val) {
        if (val == null) return null;
        Object s = coerce(val, String.class);
        return s == null ? null : String.valueOf(s);
    }

    private long compositeKeyValue(CompositeIndexInfo ci, List<QueryWrapper.Criterion> criteria) {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < ci.columns.size(); i++) {
            if (i > 0) sb.append('\u0001');
            IndexedColInfo col = ci.columns.get(i);
            Object val = null;
            for (QueryWrapper.Criterion c : criteria) {
                if (c.op == QueryWrapper.Op.EQ && c.col.equals(col.columnName)) {
                    val = c.a;
                    break;
                }
            }
            if (val == null) {
                sb.append('\u0000');
            } else if (col.valueKind == DsEqIndexStore.IndexedValueKind.LONG) {
                sb.append(toIndexLongValue(val, col));
            } else {
                sb.append(toIndexStringValue(val));
            }
        }
        return fnv1a64(sb.toString());
    }

    private static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    // ---- P17 T2: 复合索引 128bit VarInt Packing 编码 / 解码 / Comparator ----

    private static final int COMP_COL_BYTES_LONG = 8;
    private static final int COMP_COL_BYTES_STRING = 32;

    /**
     * 无符号 字典序 Comparator<byte[]>：逐字节 0-255 比较，短补 0x00 视作 一致。
     * 用于 compKey 的 Arrays.sort / TreeMap / prefix EQ 判定。
     */
    public static final Comparator<byte[]> COMPOSITE_KEY_UNSIGNED = (a, b) -> {
        if (a == b) return 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int va = a[i] & 0xFF;
            int vb = b[i] & 0xFF;
            int c = Integer.compare(va, vb);
            if (c != 0) return c;
        }
        return Integer.compare(a.length, b.length);
    };

    private static int colBytes(IndexedColInfo col) {
        if (col.valueKind == DsEqIndexStore.IndexedValueKind.LONG) return COMP_COL_BYTES_LONG;
        return COMP_COL_BYTES_STRING;
    }

    /**
     * 编码：values[i] 对应 ci.columns[i]（按 ci 列顺序 1:1），定长 8B / 32B 拼接。
     * LONG 列：unsigned(v - Long.MIN_VALUE) 写入 BIG_ENDIAN 8B → 字典序 = 数值 ASC。
     * STRING 列：UTF-8 字节 → 截断/补 0x00 至 32B → 字典序 = String.compareTo ASC（不含 \u0000 时）。
     */
    static byte[] encodeCompositeKey(CompositeIndexInfo ci, Object[] values) {
        if (ci == null) throw new IllegalArgumentException("ci cannot be null");
        if (values == null) throw new IllegalArgumentException("values cannot be null");
        int cols = ci.columns.size();
        if (values.length != cols) throw new IllegalArgumentException(
                "values length mismatch: expected=" + cols + " actual=" + values.length);
        int total = 0;
        for (int i = 0; i < cols; i++) total += colBytes(ci.columns.get(i));
        byte[] out = new byte[total];
        int off = 0;
        for (int i = 0; i < cols; i++) {
            IndexedColInfo col = ci.columns.get(i);
            Object v = values[i];
            if (col.valueKind == DsEqIndexStore.IndexedValueKind.LONG) {
                long raw = toIndexLongValueStatic(v, col);
                long u = raw - Long.MIN_VALUE; // unsigned shift
                for (int b = 7; b >= 0; b--) {
                    out[off + b] = (byte) (u & 0xFFL);
                    u >>>= 8;
                }
                off += COMP_COL_BYTES_LONG;
            } else {
                String s = (v == null) ? null : String.valueOf(coerceStatic(v, String.class));
                byte[] sb = (s == null) ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
                int copy = Math.min(COMP_COL_BYTES_STRING, sb.length);
                System.arraycopy(sb, 0, out, off, copy);
                // 剩余 字节 保留 0x00
                off += COMP_COL_BYTES_STRING;
            }
        }
        return out;
    }

    /**
     * 解码：前 k 列 还原 为 Object[]（k<=ci.columns.size()）。
     * LONG 列：BIG_ENDIAN 8B 还原 unsigned → raw = u + Long.MIN_VALUE。
     * STRING 列：UTF-8 字节，截断 结尾 连续 0x00 pad。
     */
    static Object[] decodeCompositeKeyFirstKCols(byte[] key, int k, CompositeIndexInfo ci) {
        if (key == null) throw new IllegalArgumentException("key cannot be null");
        if (ci == null) throw new IllegalArgumentException("ci cannot be null");
        if (k <= 0 || k > ci.columns.size()) throw new IllegalArgumentException(
                "k out of range: " + k + "/" + ci.columns.size());
        Object[] out = new Object[k];
        int off = 0;
        for (int i = 0; i < k; i++) {
            IndexedColInfo col = ci.columns.get(i);
            if (col.valueKind == DsEqIndexStore.IndexedValueKind.LONG) {
                long u = 0L;
                for (int b = 0; b < 8; b++) {
                    u = (u << 8) | (key[off + b] & 0xFFL);
                }
                long raw = u + Long.MIN_VALUE;
                // 按列 类型 还原
                Class<?> ft = col.fieldType;
                if (ft == long.class || ft == Long.class) out[i] = raw;
                else if (ft == int.class || ft == Integer.class) out[i] = (int) raw;
                else if (ft == short.class || ft == Short.class) out[i] = (short) raw;
                else if (ft == byte.class || ft == Byte.class) out[i] = (byte) raw;
                else if (ft == boolean.class || ft == Boolean.class) out[i] = (raw != 0L);
                else if (ft == java.util.Date.class) out[i] = new java.util.Date(raw);
                else if (ft == java.sql.Timestamp.class) out[i] = new java.sql.Timestamp(raw);
                else out[i] = raw;
                off += COMP_COL_BYTES_LONG;
            } else {
                int end = off + COMP_COL_BYTES_STRING;
                int tail = end;
                while (tail > off && key[tail - 1] == 0x00) tail--;
                int len = tail - off;
                String s = (len == 0) ? "" : new String(key, off, len, StandardCharsets.UTF_8);
                out[i] = s;
                off += COMP_COL_BYTES_STRING;
            }
        }
        return out;
    }

    private static long toIndexLongValueStatic(Object val, IndexedColInfo col) {
        if (val == null) return 0L;
        Class<?> ft = col.fieldType;
        Object v = coerceStatic(val, ft);
        if (v == null) return 0L;
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Short s) return s.longValue();
        if (v instanceof Byte b) return b.longValue();
        if (v instanceof Boolean bo) return bo ? 1L : 0L;
        if (v instanceof java.util.Date d) return d.getTime();
        return 0L;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerceStatic(Object val, Class<?> target) {
        if (val == null || target == null || target.isInstance(val)) return val;
        try {
            if (target == String.class) return String.valueOf(val);
            if (target == long.class || target == Long.class) {
                if (val instanceof Number n) return n.longValue();
                return Long.parseLong(String.valueOf(val));
            }
            if (target == int.class || target == Integer.class) {
                if (val instanceof Number n) return n.intValue();
                return Integer.parseInt(String.valueOf(val));
            }
            if (target == short.class || target == Short.class) {
                if (val instanceof Number n) return n.shortValue();
                return Short.parseShort(String.valueOf(val));
            }
            if (target == byte.class || target == Byte.class) {
                if (val instanceof Number n) return n.byteValue();
                return Byte.parseByte(String.valueOf(val));
            }
            if (target == boolean.class || target == Boolean.class) {
                if (val instanceof Boolean b) return b;
                String s = String.valueOf(val);
                if ("true".equalsIgnoreCase(s) || "1".equals(s)) return Boolean.TRUE;
                return Boolean.FALSE;
            }
            if (java.util.Date.class.isAssignableFrom(target)) {
                if (val instanceof Number n) return new java.util.Date(n.longValue());
            }
        } catch (Exception ignore) { }
        return val;
    }

    private static long fnvBytes(byte[] b) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < b.length; i++) {
            h ^= (b[i] & 0xFFL);
            h *= 0x100000001b3L;
        }
        return h == 0L ? 1L : h;
    }

    // ---- P17 T3: 复合索引 range map 懒 构建 + seek/slice ----
    private java.util.NavigableMap<byte[], List<Long>> ensureCompositeRangeMap(CompositeIndexInfo ci) {
        Map<CompositeIndexInfo, java.util.NavigableMap<byte[], List<Long>>> maps = compRangeIndexMaps;
        if (maps == null) {
            synchronized (compositeRangeLock) {
                if ((maps = compRangeIndexMaps) == null) {
                    maps = new java.util.concurrent.ConcurrentHashMap<>(4);
                    compRangeIndexMaps = maps;
                }
            }
        }
        java.util.NavigableMap<byte[], List<Long>> m = maps.get(ci);
        if (m != null) return m;
        synchronized (compositeRangeLock) {
            if ((m = maps.get(ci)) != null) return m;
            m = new java.util.TreeMap<>(COMPOSITE_KEY_UNSIGNED);
            // 扫 所有 实体 ，按 ci 列 顺序 encode 填 入 treeMap
            Object[] buf = new Object[ci.columns.size()];
            Iterator<Long> it = primaryIdIterator();
            while (it.hasNext()) {
                Long id = it.next();
                if (id == null || id == 0L) continue;
                T e = db.getTable(persistentClass, id, Long.MAX_VALUE);
                if (e == null) continue;
                for (int i = 0; i < buf.length; i++) {
                    buf[i] = readByColumn(e, ci.columns.get(i).columnName);
                }
                byte[] key = encodeCompositeKey(ci, buf);
                long idv = id;
                m.computeIfAbsent(key, k -> new ArrayList<>(2)).add(idv);
            }
            maps.put(ci, m);
            return m;
        }
    }

    /** 判断 orders 是否 100% 匹配 ci 列 顺序 + 全 方向 一 致（all asc / all desc 同 向）。返回 1=ASC, -1=DESC, 0=不匹配。 */
    private int ordersMatchCompositeIndex(List<QueryWrapper.Order> orders, CompositeIndexInfo ci) {
        if (orders == null || orders.isEmpty() || orders.size() > ci.columns.size()) return 0;
        // 前 k 列 一 一 匹配
        for (int i = 0; i < orders.size(); i++) {
            if (!orders.get(i).col.equals(ci.columns.get(i).columnName)) return 0;
        }
        // 方向 全 一 致
        boolean allAsc = orders.get(0).asc;
        for (int i = 1; i < orders.size(); i++) if (orders.get(i).asc != allAsc) return 0;
        return allAsc ? 1 : -1;
    }

    /**
     * 从 criteria 构造 前缀 EQ + RANGE 的 compKey [lo, hi) 两 端（字典序）。
     * 回 数组 长 2： [0]=lo（可能 null）, [1]=hi（可能 null）, [2]=eqPrefixLen, [3]=rangeColIdx(-1 无 range)。
     * 仅 支持 前缀 k EQ + 最 后 1 列 RANGE 组合，否则 返回 null。
     */
    private Object[] buildCompositeRangeBounds(CompositeIndexInfo ci, List<QueryWrapper.Criterion> criteria) {
        int cols = ci.columns.size();
        // step1: 收集 每 列 EQ 值
        Object[] eqVals = new Object[cols];
        boolean[] eqOk = new boolean[cols];
        QueryWrapper.Criterion rangeC = null;
        int rangeColIdx = -1;
        for (int i = 0; i < cols; i++) {
            IndexedColInfo col = ci.columns.get(i);
            for (QueryWrapper.Criterion c : criteria) {
                if (!c.col.equals(col.columnName)) continue;
                if (c.op == QueryWrapper.Op.EQ) {
                    eqVals[i] = c.a;
                    eqOk[i] = true;
                } else if (c.op == QueryWrapper.Op.GT || c.op == QueryWrapper.Op.GE
                        || c.op == QueryWrapper.Op.LT || c.op == QueryWrapper.Op.LE
                        || c.op == QueryWrapper.Op.BETWEEN) {
                    if (rangeC == null) {
                        rangeC = c;
                        rangeColIdx = i;
                    }
                }
            }
        }
        // 校验：EQ 必须 是 严格 前缀 k；若 有 RANGE，RANGE 列 = k（紧接 EQ 后）
        int k = 0;
        while (k < cols && eqOk[k]) k++;
        if (rangeC != null && rangeColIdx != k) return null;
        int totalCols = (rangeC == null) ? k : (k + 1);
        if (totalCols == 0) return null;
        // 构造 lo / hi：先 EQ 前 k 列 值 填 入 eqBuf；再 加 RANGE 边界
        Object[] eqBuf = new Object[k];
        for (int i = 0; i < k; i++) eqBuf[i] = eqVals[i];
        byte[] eqPrefixKey = (k == 0) ? new byte[0] : encodePrefixKey(ci, eqBuf, k);
        byte[] lo, hi;
        if (rangeC == null) {
            // 仅 EQ 前缀：lo = prefix; hi = prefix 字典序 上 一 级（末 字节 +1 进位）
            lo = eqPrefixKey;
            hi = incrementBytes(eqPrefixKey);
            return new Object[]{lo, hi, k, -1};
        }
        // EQ k + RANGE(k 列)：分别 取 loVal / hiVal
        IndexedColInfo rc = ci.columns.get(rangeColIdx);
        Object loVal = null, hiVal = null;
        boolean loInc = true, hiInc = false;
        switch (rangeC.op) {
            case GT:  loVal = rangeC.a; loInc = false; hiInc = true; break; // < +∞
            case GE:  loVal = rangeC.a; loInc = true;  hiInc = true; break;
            case LT:  hiVal = rangeC.a; hiInc = false; break;
            case LE:  hiVal = rangeC.a; hiInc = true; break;
            case BETWEEN:
                loVal = rangeC.a; hiVal = rangeC.b;
                loInc = true; hiInc = true;
                break;
            default: return null;
        }
        // 构造 loKey = eqPrefix + encode(1 列 loVal)；hiKey = hiVal 版本
        byte[] loCol = encodeSingleColumn(rc, loVal, loInc, true);
        byte[] hiCol = encodeSingleColumn(rc, hiVal, hiInc, false);
        lo = concatBytes(eqPrefixKey, loCol);
        hi = (hiCol == null) ? incrementBytes(concatBytes(eqPrefixKey, maxBytesOfColumn(rc)))
                             : concatBytes(eqPrefixKey, hiCol);
        return new Object[]{lo, hi, k, rangeColIdx};
    }

    private byte[] encodePrefixKey(CompositeIndexInfo ci, Object[] values, int k) {
        int total = 0;
        for (int i = 0; i < k; i++) total += colBytes(ci.columns.get(i));
        byte[] out = new byte[total];
        int off = 0;
        for (int i = 0; i < k; i++) {
            IndexedColInfo col = ci.columns.get(i);
            if (col.valueKind == DsEqIndexStore.IndexedValueKind.LONG) {
                long raw = toIndexLongValueStatic(values[i], col);
                long u = raw - Long.MIN_VALUE;
                for (int b = 7; b >= 0; b--) { out[off + b] = (byte) (u & 0xFFL); u >>>= 8; }
                off += COMP_COL_BYTES_LONG;
            } else {
                String s = (values[i] == null) ? null : String.valueOf(coerceStatic(values[i], String.class));
                byte[] sb = (s == null) ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
                int copy = Math.min(COMP_COL_BYTES_STRING, sb.length);
                System.arraycopy(sb, 0, out, off, copy);
                off += COMP_COL_BYTES_STRING;
            }
        }
        return out;
    }

    /** 单 列 编码（字典序）。loMode=true → 下界；loMode=false → 上界（hiVal 不 含 用 exact，含 用 increment）。 */
    private byte[] encodeSingleColumn(IndexedColInfo col, Object val, boolean inclusive, boolean loMode) {
        if (val == null) {
            return loMode ? new byte[colBytes(col)] : null;
        }
        if (col.valueKind == DsEqIndexStore.IndexedValueKind.LONG) {
            byte[] out = new byte[COMP_COL_BYTES_LONG];
            long raw = toIndexLongValueStatic(val, col);
            // 调整：不 含 下界 → raw+1；不 含 上界 → raw；含 则 相反
            long u;
            if (loMode) {
                u = (inclusive ? raw : (raw + 1L)) - Long.MIN_VALUE;
            } else {
                u = (inclusive ? (raw + 1L) : raw) - Long.MIN_VALUE;
            }
            for (int b = 7; b >= 0; b--) { out[b] = (byte) (u & 0xFFL); u >>>= 8; }
            return out;
        }
        // STRING
        String s = String.valueOf(coerceStatic(val, String.class));
        byte[] sb = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[COMP_COL_BYTES_STRING];
        int copy = Math.min(COMP_COL_BYTES_STRING, sb.length);
        System.arraycopy(sb, 0, out, 0, copy);
        // 不 含 处理：loMode 下 +0 即可 （compareTo 字典序 自然 处理）；hiMode 不 含 则 末 有效 字节 +0 进位；
        if (!loMode && !inclusive) {
            // 把 最后 非 0 字节 +1 进位 （实现 简易，遇 0xFF 则 向 前 进 位，全 0xFF 则 回 null 让 调用 方 用 max）
            int p = COMP_COL_BYTES_STRING - 1;
            while (p >= 0 && out[p] == 0) p--;
            if (p < 0) return null; // 空字符串 作为 上界 不 含 视同 -∞，不 会 命中 任何 东西（给 null 最 安全）
            while (p >= 0) {
                if (out[p] == (byte) 0xFF) {
                    out[p] = 0;
                    p--;
                } else {
                    out[p] = (byte) (out[p] + 1);
                    break;
                }
            }
            if (p < 0) return null;
        }
        return out;
    }

    private byte[] maxBytesOfColumn(IndexedColInfo col) {
        byte[] out = new byte[colBytes(col)];
        Arrays.fill(out, (byte) 0xFF);
        return out;
    }

    private byte[] incrementBytes(byte[] b) {
        byte[] out = Arrays.copyOf(b, b.length);
        int p = out.length - 1;
        while (p >= 0) {
            if (out[p] == (byte) 0xFF) { out[p] = 0; p--; }
            else { out[p] = (byte) (out[p] + 1); return out; }
        }
        // 全 FF，进位 1 位
        byte[] out2 = new byte[out.length + 1];
        out2[0] = 1;
        return out2;
    }

    private byte[] concatBytes(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** 从 compositeRangeMap 范围 迭代 收集 rowIds，取 end+1 条 做 LIMIT TopN。返回 ids（去 重 顺序 保留）。 */
    private List<Long> collectRowIdsInRange(java.util.NavigableMap<byte[], List<Long>> map,
                                            byte[] lo, byte[] hi, boolean asc, int start, int end) {
        if (map.isEmpty()) return Collections.emptyList();
        java.util.NavigableMap<byte[], List<Long>> view = map;
        if (lo != null || hi != null) {
            byte[] fromKey = (lo == null) ? map.firstKey() : lo;
            byte[] toKey   = (hi == null) ? incrementBytes(map.lastKey()) : hi;
            boolean fromInc = true;
            boolean toInc   = false;
            view = map.subMap(fromKey, fromInc, toKey, toInc);
        }
        if (!asc) view = view.descendingMap();
        int needed = (end < 0) ? Integer.MAX_VALUE : (end + 1);
        int skip = Math.max(0, start);
        List<Long> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        int pass = 0;
        for (java.util.Map.Entry<byte[], List<Long>> en : view.entrySet()) {
            for (Long id : en.getValue()) {
                if (seen.add(id)) {
                    if (pass < skip) { pass++; continue; }
                    out.add(id);
                    if (out.size() >= needed) return out;
                }
            }
        }
        return out;
    }

    private QueryWrapper.Criterion pickBestIndexedCriterion(QueryWrapper<T> wrapper) {
        List<QueryWrapper.Criterion> usable = collectIndexableCriteria(wrapper.criteria());
        QueryWrapper.Criterion eqLong = null, eqString = null, rangeLong = null;
        for (QueryWrapper.Criterion c : usable) {
            IndexedColInfo info = indexedColInfo.get(c.col);
            switch (c.op) {
                case EQ:
                    if (info.valueKind == DsEqIndexStore.IndexedValueKind.LONG && eqLong == null) eqLong = c;
                    else if (info.valueKind == DsEqIndexStore.IndexedValueKind.STRING && eqString == null) eqString = c;
                    break;
                default:
                    if (rangeLong == null) rangeLong = c;
                    break;
            }
        }
        if (eqLong != null) return eqLong;
        if (eqString != null) return eqString;
        return rangeLong;
    }

    private long[] candidateRowIdsFromIndex(QueryWrapper.Criterion best) throws IOException {
        IndexedColInfo info = indexedColInfo.get(best.col);
        if (info == null) return null;
        final boolean isString = info.valueKind == DsEqIndexStore.IndexedValueKind.STRING;
        if (isString && (best.op == QueryWrapper.Op.GT || best.op == QueryWrapper.Op.GE
                || best.op == QueryWrapper.Op.LT || best.op == QueryWrapper.Op.LE
                || best.op == QueryWrapper.Op.BETWEEN || best.op == QueryWrapper.Op.EQ)) {
            StringIndexBridge b = ensureStringBridge();
            if (b != null) {
                long cid = b.colId(info.columnName);
                if (cid != 0L) {
                    try {
                        String entityClassName = persistentClass.getName();
                        int limit = 0;
                        boolean asc = true;
                        switch (best.op) {
                            case EQ: {
                                String s = (best.a == null) ? null : String.valueOf(coerceStatic(best.a, String.class));
                                byte[] sb = (s == null) ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
                                return (long[]) b.mhFindEq.invoke(b.storeInstance, entityClassName, cid, sb, limit);
                            }
                            case GT: {
                                String s = (best.a == null) ? null : String.valueOf(coerceStatic(best.a, String.class));
                                byte[] sb = (s == null) ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
                                return (long[]) b.mhGt.invoke(b.storeInstance, entityClassName, cid, sb, asc, limit);
                            }
                            case GE: {
                                String s = (best.a == null) ? null : String.valueOf(coerceStatic(best.a, String.class));
                                byte[] sb = (s == null) ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
                                return (long[]) b.mhGe.invoke(b.storeInstance, entityClassName, cid, sb, asc, limit);
                            }
                            case LT: {
                                String s = (best.a == null) ? null : String.valueOf(coerceStatic(best.a, String.class));
                                byte[] sb = (s == null) ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
                                return (long[]) b.mhLt.invoke(b.storeInstance, entityClassName, cid, sb, asc, limit);
                            }
                            case LE: {
                                String s = (best.a == null) ? null : String.valueOf(coerceStatic(best.a, String.class));
                                byte[] sb = (s == null) ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
                                return (long[]) b.mhLe.invoke(b.storeInstance, entityClassName, cid, sb, asc, limit);
                            }
                            case BETWEEN: {
                                String sa = (best.a == null) ? null : String.valueOf(coerceStatic(best.a, String.class));
                                String sb = (best.b == null) ? null : String.valueOf(coerceStatic(best.b, String.class));
                                byte[] ab = (sa == null) ? new byte[0] : sa.getBytes(StandardCharsets.UTF_8);
                                byte[] bb = (sb == null) ? new byte[0] : sb.getBytes(StandardCharsets.UTF_8);
                                return (long[]) b.mhBetween.invoke(b.storeInstance,
                                        entityClassName, cid, ab, true, bb, true, asc, limit);
                            }
                            default:
                                break;
                        }
                    } catch (Throwable t) {
                        // fall-through 原 流 程
                    }
                }
            }
            if (best.op != QueryWrapper.Op.EQ) return null; // 原 P16 只 支 持 EQ 行 存 FNV
        }
        try (DsEqIndexStore idx = openIndexStore(info)) {
            switch (best.op) {
                case EQ:
                    if (info.valueKind == DsEqIndexStore.IndexedValueKind.LONG) {
                        return idx.findByIndex(toIndexLongValue(best.a, info));
                    } else {
                        return idx.findByIndex(toIndexStringValue(best.a));
                    }
                case GT:
                    return idx.findByGt(toIndexLongValue(best.a, info));
                case GE:
                    return idx.findByGte(toIndexLongValue(best.a, info));
                case LT:
                    return idx.findByLt(toIndexLongValue(best.a, info));
                case LE:
                    return idx.findByLte(toIndexLongValue(best.a, info));
                case BETWEEN:
                    return idx.findByBetween(toIndexLongValue(best.a, info), toIndexLongValue(best.b, info));
                default:
                    return null;
            }
        }
    }

    private java.util.Set<Long> rowIdSetFromIndex(QueryWrapper.Criterion c) throws IOException {
        long[] ids = candidateRowIdsFromIndex(c);
        if (ids == null || ids.length == 0) return Collections.emptySet();
        java.util.HashSet<Long> set = new java.util.HashSet<>(Math.max(4, ids.length));
        for (long id : ids) {
            if (id != DsEqIndexStore.NOT_FOUND) set.add(id);
        }
        return set;
    }

    private java.util.Set<Long> rowIdSetFromInCriterion(QueryWrapper.Criterion c) throws IOException {
        IndexedColInfo info = indexedColInfo.get(c.col);
        if (info == null || c.a == null || !(c.a instanceof Collection<?> vals)) {
            return Collections.emptySet();
        }
        java.util.HashSet<Long> set = new java.util.HashSet<>();
        try (DsEqIndexStore idx = openIndexStore(info)) {
            for (Object val : vals) {
                long[] ids;
                if (info.valueKind == DsEqIndexStore.IndexedValueKind.LONG) {
                    ids = idx.findByIndex(toIndexLongValue(val, info));
                } else {
                    ids = idx.findByIndex(toIndexStringValue(val));
                }
                if (ids != null) {
                    for (long id : ids) {
                        if (id != DsEqIndexStore.NOT_FOUND) set.add(id);
                    }
                }
            }
        }
        return set;
    }

    private List<QueryWrapper.Criterion> collectIndexableCriteria(List<QueryWrapper.Criterion> criteria) {
        if (!hasIndexedColumns || criteria == null || criteria.isEmpty()) {
            return Collections.emptyList();
        }
        List<QueryWrapper.Criterion> out = new ArrayList<>(4);
        for (QueryWrapper.Criterion c : criteria) {
            IndexedColInfo info = indexedColInfo.get(c.col);
            if (info == null || info.valueKind == null) continue;
            switch (c.op) {
                case EQ:
                    out.add(c);
                    break;
                case GT: case GE: case LT: case LE: case BETWEEN:
                    out.add(c);
                    break;
                case IN:
                    if (c.a instanceof Collection<?> col && !col.isEmpty()) out.add(c);
                    break;
                default: break;
            }
        }
        return out;
    }

    private Iterator<Long> primaryIdIterator() {
        if (idSet == null) return java.util.Collections.emptyIterator();
        return idSet.iterator();
    }

    private void applyOrders(QueryWrapper<T> wrapper, OrderByProp[] orders) {
        if (orders == null || orders.length == 0) {
            return;
        }
        for (OrderByProp o : orders) {
            if (o == null) {
                continue;
            }
            String col = getColumnName(o.value());
            if (col == null) {
                continue;
            }
            if (o.asc()) {
                wrapper.orderByAsc(col);
            } else {
                wrapper.orderByDesc(col);
            }
        }
    }

    private OrderWrapper parseOrders(String orders) {
        OrderWrapper ow = OrderWrapper.build();
        if (orders == null || orders.isBlank()) {
            return ow;
        }
        String[] parts = orders.split(",");
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty()) {
                continue;
            }
            String[] seg = s.split("\\s+");
            String prop = seg[0].trim();
            boolean asc = seg.length < 2 || !"desc".equalsIgnoreCase(seg[1].trim());
            if (asc) {
                ow.orderAsc(prop);
            } else {
                ow.orderDesc(prop);
            }
        }
        return ow;
    }

    private void applyOrderWrapper(QueryWrapper<T> wrapper, OrderWrapper ow) {
        if (ow == null) {
            return;
        }
        for (OrderWrapper.Item i : ow.items()) {
            String col = getColumnName(i.prop);
            if (col == null) {
                continue;
            }
            if (i.asc) {
                wrapper.orderByAsc(col);
            } else {
                wrapper.orderByDesc(col);
            }
        }
    }

    private List<T> sliceEntities(QueryWrapper<T> wrapper, int start, int end) {
        int[] hint = SLICE_HINT.get();
        hint[0] = start; hint[1] = end;
        try {
            List<T> all = listEntities(wrapper);
            // 若 复合 range 下 推 已 内 置 了 TopN，则 all 已经 是 [start,end] 闭区间 了（size ≤ end-start+1），直接 返
            boolean pushed = Boolean.TRUE.equals(SKIP_SORT_HINT.get()) && (end >= 0) && (all.size() <= end - start + 1 + 16);
            if (pushed) return all;
            if (start <= 0 && end <= 0) return all;
            int from = Math.max(0, start);
            int to = Math.min(all.size() - 1, end);
            if (to < from || all.isEmpty()) return new ArrayList<>();
            return new ArrayList<>(all.subList(from, to + 1));
        } finally {
            hint[0] = -1; hint[1] = -1;
        }
    }

    private List<T> listEntities(QueryWrapper<T> wrapper) {
        List<T> out = new ArrayList<>();
        for (Long id : candidateIdsForQuery(wrapper)) {
            T e = db.getTable(persistentClass, id, readTimestamp);
            if (e == null || !matchesAll(wrapper, e)) {
                continue;
            }
            out.add(e);
        }
        sort(out, wrapper);
        return out;
    }

    private Iterable<Long> candidateIdsForQuery(QueryWrapper<T> wrapper) {
        // 把任意嵌套 AND/OR 结构规范化为 DNF：顶层 OR 的每个分支是一个 AND 合取式
        List<List<QueryWrapper.Criterion>> dnf = flattenDnf(wrapper);

        // 任意合取式为空（TRUE）时，OR 结果恒为 TRUE，必须全表扫 — 但 如果 有 复合索引 ORDER BY + LIMIT，仍 可 走 range（TopN 下 推）
        boolean allEmpty = true;
        for (List<QueryWrapper.Criterion> conj : dnf) if (!conj.isEmpty()) { allEmpty = false; break; }

        // ---- P17 复合 range / order / limit 下 推 优 先 ----
        if (hasCompositeIndexes && dnf.size() == 1) {
            try {
                List<QueryWrapper.Criterion> criteria = dnf.get(0);
                // 选 bestCi：1) 若 有 criteria 则 longest EqPrefix 大 的；2) 若 无 criteria 但 有 orders，选 orders 匹配 列 数 最 多 的
                CompositeIndexInfo bestCi = null;
                int bestScore = -1;
                for (CompositeIndexInfo ci : compositeIndexInfo) {
                    int eq = longestEqPrefix(ci, criteria);
                    int om = ordersMatchCompositeIndex(wrapper.orders(), ci);
                    int orderCol = (om == 0) ? 0 : wrapper.orders().size();
                    int total = eq * 1000 + orderCol * 10 + (criteria.isEmpty() ? 1 : 0);
                    if (total > bestScore) { bestScore = total; bestCi = ci; }
                }
                if (bestCi != null) {
                    // 检查 range bounds（若 criteria 非 空）
                    Object[] bounds = (criteria.isEmpty()) ? null : buildCompositeRangeBounds(bestCi, criteria);
                    // 若 bounds 构造 出 来 表示 不 支持 复合 range；但 只要 orders 匹配 + bestCi 存在，仍 可 走 全 范围 以 配合 skip sort / limit
                    int[] sl = SLICE_HINT.get();
                    int s = sl[0], en = sl[1];
                    int orderMatch = ordersMatchCompositeIndex(wrapper.orders(), bestCi);
                    if (bounds != null || criteria.isEmpty() || longestEqPrefix(bestCi, criteria) >= 1) {
                        java.util.NavigableMap<byte[], List<Long>> rm = ensureCompositeRangeMap(bestCi);
                        boolean asc = (orderMatch >= 0);
                        byte[] lo = null, hi = null;
                        if (bounds != null) { lo = (byte[]) bounds[0]; hi = (byte[]) bounds[1]; }
                        List<Long> ids = collectRowIdsInRange(rm, lo, hi, asc, s, en);
                        if (!ids.isEmpty()) {
                            // LIMIT + order 匹配 → 标记 SKIP_SORT_HINT
                            if (orderMatch != 0 && !wrapper.orders().isEmpty()) {
                                SKIP_SORT_HINT.set(Boolean.TRUE);
                            }
                            return ids;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (allEmpty) return fullScanIds();

        // 单合取式时优先尝试复合索引最左前缀（旧 FNV 模式，BackwardCompat）
        if (hasCompositeIndexes && dnf.size() == 1) {
            try {
                List<QueryWrapper.Criterion> criteria = dnf.get(0);
                CompositeIndexInfo bestCi = selectBestCompositeIndex(criteria);
                if (bestCi != null) {
                    try (DsEqIndexStore idx = openCompositeIndexStore(bestCi)) {
                        long key = compositeKeyValue(bestCi, criteria);
                        long[] ids = idx.findByIndex(key);
                        if (ids != null && ids.length > 0) {
                            java.util.Set<Long> set = new java.util.HashSet<>(Math.max(4, ids.length));
                            for (long id : ids) {
                                if (id != DsEqIndexStore.NOT_FOUND) set.add(id);
                            }
                            if (!set.isEmpty()) return new ArrayList<>(set);
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }

        // 每个 DNF 合取式分别做单列/多列索引 narrowing，再取并集
        java.util.Set<Long> union = null;
        for (List<QueryWrapper.Criterion> conj : dnf) {
            java.util.Set<Long> set = narrowByCriteria(conj);
            if (set == null) continue;
            if (union == null) {
                union = new java.util.HashSet<>(set);
            } else {
                union.addAll(set);
            }
        }

        if (union != null) {
            return new ArrayList<>(union);
        }

        // 所有合取式都无法索引 narrowing，fallback 全表扫
        return fullScanIds();
    }

    private List<Long> fullScanIds() {
        List<Long> all = new ArrayList<>();
        Iterator<Long> it = primaryIdIterator();
        while (it.hasNext()) {
            Long id = it.next();
            if (id != null && id != 0L) {
                all.add(id);
            }
        }
        return all;
    }

    /**
     * 将 QueryWrapper 的嵌套 OR/AND 结构展开为析取范式（DNF）。
     * 返回的每个 List<Criterion> 代表一个 AND 合取式；空列表表示 TRUE。
     */
    private List<List<QueryWrapper.Criterion>> flattenDnf(QueryWrapper<T> wrapper) {
        List<List<QueryWrapper.Criterion>> result = new ArrayList<>();
        if (wrapper == null) {
            result.add(new ArrayList<>());
            return result;
        }
        List<QueryWrapper.Criterion> base = new ArrayList<>(wrapper.criteria());
        if (wrapper.orBranches().isEmpty()) {
            result.add(base);
            return result;
        }
        for (QueryWrapper<T> branch : wrapper.orBranches()) {
            for (List<QueryWrapper.Criterion> sub : flattenDnf(branch)) {
                List<QueryWrapper.Criterion> combined = new ArrayList<>(base.size() + sub.size());
                combined.addAll(base);
                combined.addAll(sub);
                result.add(combined);
            }
        }
        return result;
    }

    /**
     * 对一组 AND 连接的 Criterion 做索引 narrowing。
     * 返回 null 表示没有可用索引；返回空集合表示确定无命中。
     */
    private java.util.Set<Long> narrowByCriteria(List<QueryWrapper.Criterion> criteria) {
        if (!hasIndexedColumns || criteria == null || criteria.isEmpty()) {
            return null;
        }
        List<QueryWrapper.Criterion> usable = collectIndexableCriteria(criteria);
        if (usable.isEmpty()) {
            return null;
        }
        try {
            List<java.util.Set<Long>> sets = new ArrayList<>(usable.size());
            for (QueryWrapper.Criterion c : usable) {
                java.util.Set<Long> s = (c.op == QueryWrapper.Op.IN)
                        ? rowIdSetFromInCriterion(c)
                        : rowIdSetFromIndex(c);
                if (s == null || s.isEmpty()) {
                    return Collections.emptySet();
                }
                sets.add(s);
            }
            // cost-based driver：优先从最小候选集开始交集
            sets.sort(Comparator.comparingInt(java.util.Set::size));
            java.util.Set<Long> driver = new java.util.HashSet<>(sets.get(0));
            for (int i = 1; i < sets.size(); i++) {
                driver.retainAll(sets.get(i));
                if (driver.isEmpty()) break;
            }
            return driver;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private CompositeIndexInfo selectBestCompositeIndex(List<QueryWrapper.Criterion> criteria) {
        CompositeIndexInfo bestCi = null;
        int bestPrefix = 0;
        for (CompositeIndexInfo ci : compositeIndexInfo) {
            int prefix = longestEqPrefix(ci, criteria);
            if (prefix > bestPrefix) {
                bestPrefix = prefix;
                bestCi = ci;
            }
        }
        return bestCi;
    }

    private int longestEqPrefix(CompositeIndexInfo ci, List<QueryWrapper.Criterion> criteria) {
        int matched = 0;
        for (IndexedColInfo col : ci.columns) {
            boolean found = false;
            for (QueryWrapper.Criterion c : criteria) {
                if (c.op == QueryWrapper.Op.EQ && c.col.equals(col.columnName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
            matched++;
        }
        return matched;
    }

    private boolean matchesAll(QueryWrapper<T> wrapper, T entity) {
        if (wrapper == null) {
            return true;
        }
        for (QueryWrapper.Criterion c : wrapper.criteria()) {
            if (!matches(entity, c)) {
                return false;
            }
        }
        if (wrapper.orBranches().isEmpty()) {
            return true;
        }
        for (QueryWrapper<T> branch : wrapper.orBranches()) {
            if (matchesAll(branch, entity)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(T entity, QueryWrapper.Criterion c) {
        if (c == null) {
            return true;
        }
        Object fieldValue = readByColumn(entity, c.col);
        return switch (c.op) {
            case EQ -> eq(fieldValue, c.a);
            case NE -> !eq(fieldValue, c.a);
            case GT -> cmp(fieldValue, c.a) > 0;
            case GE -> cmp(fieldValue, c.a) >= 0;
            case LT -> cmp(fieldValue, c.a) < 0;
            case LE -> cmp(fieldValue, c.a) <= 0;
            case BETWEEN -> cmp(fieldValue, c.a) >= 0 && cmp(fieldValue, c.b) <= 0;
            case IN -> in(fieldValue, c.a);
            case NOT_IN -> !in(fieldValue, c.a);
            case LIKE -> like(fieldValue, c.a, 0);
            case LIKE_LEFT -> like(fieldValue, c.a, 1);
            case LIKE_RIGHT -> like(fieldValue, c.a, 2);
            case IS_NULL -> fieldValue == null;
            case IS_NOT_NULL -> fieldValue != null;
            case IN_SUBQUERY -> false;
            case EXISTS -> exists(entity, c);
            case NOT_EXISTS -> !exists(entity, c);
        };
    }

    private boolean exists(T entity, QueryWrapper.Criterion c) {
        if (c.a == null || !(c.a instanceof Class<?> cls) || !DsTableAdapter.class.isAssignableFrom(cls)) {
            return false;
        }
        QueryWrapper.ExistsSpec spec = (c.b instanceof QueryWrapper.ExistsSpec)
                ? (QueryWrapper.ExistsSpec) c.b : null;
        QueryWrapper<?> sub = (spec != null) ? spec.subWrapper : null;
        String relatedField = (spec != null) ? spec.relatedField : null;
        String outerField = (spec != null) ? spec.outerField : null;
        try {
            @SuppressWarnings("unchecked")
            GenericManager<DsTableAdapter> mgr = new GenericManager<>((Class<DsTableAdapter>) cls);
            QueryWrapper<?> effective = sub;
            if (relatedField != null && !relatedField.isEmpty()) {
                Object outerValue = readOuterValue(entity, outerField);
                effective = correlatedWrapper(mgr, sub, relatedField, outerValue);
            }
            return mgr.count((QueryWrapper) effective) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Object readOuterValue(T entity, String outerField) {
        if (entity == null) {
            return null;
        }
        if (outerField == null || outerField.isEmpty() || "id".equals(outerField)) {
            return entity.getId();
        }
        return readByColumn(entity, outerField);
    }

    private QueryWrapper<?> correlatedWrapper(GenericManager<?> mgr, QueryWrapper<?> sub,
            String relatedField, Object outerValue) {
        QueryWrapper<?> target = mgr.buildQueryWrapper();
        if (outerValue != null) {
            target.eq(relatedField, outerValue);
        } else {
            target.isNull(relatedField);
        }
        if (sub != null) {
            target.merge(sub);
        }
        return target;
    }

    private Object readByColumn(T entity, String col) {
        if (entity == null || col == null) {
            return null;
        }
        Field f = col2FieldMap.get(col);
        if (f == null) {
            f = fieldsMap.get(col);
        }
        if (f == null) {
            return null;
        }
        try {
            return f.get(entity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean eq(Object a, Object b) {
        if (a == null) {
            return b == null;
        }
        Object bb = coerce(b, a.getClass());
        return Objects.equals(a, bb);
    }

    private int cmp(Object a, Object b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        Object bb = coerce(b, a.getClass());
        if (bb == null) {
            return 1;
        }
        if (a instanceof Comparable ca && bb instanceof Comparable cb) {
            return ca.compareTo(cb);
        }
        return String.valueOf(a).compareTo(String.valueOf(bb));
    }

    private Object coerce(Object v, Class<?> target) {
        if (v == null) {
            return null;
        }
        if (target.isInstance(v)) {
            return v;
        }
        return MyDsDatabaseUtil.convert(v, target);
    }

    private boolean in(Object fieldValue, Object setValue) {
        if (setValue == null) {
            return false;
        }
        if (setValue instanceof Collection<?> col) {
            for (Object it : col) {
                if (eq(fieldValue, it)) {
                    return true;
                }
            }
            return false;
        }
        return eq(fieldValue, setValue);
    }

    private boolean like(Object fieldValue, Object v, int kind) {
        if (fieldValue == null || v == null) {
            return false;
        }
        String a = String.valueOf(fieldValue);
        String b = String.valueOf(v);
        return switch (kind) {
            case 1 -> a.startsWith(b);
            case 2 -> a.endsWith(b);
            default -> a.contains(b);
        };
    }

    private void sort(List<T> list, QueryWrapper<T> wrapper) {
        if (Boolean.TRUE.equals(SKIP_SORT_HINT.get())) {
            SKIP_SORT_HINT.set(Boolean.FALSE);
            return;
        }
        if (wrapper == null || wrapper.orders().isEmpty() || list.size() < 2) {
            return;
        }
        Comparator<T> cmp = null;
        for (QueryWrapper.Order o : wrapper.orders()) {
            Comparator<T> next = (left, right) -> {
                int r = cmpNullsLast(readByColumn(left, o.col), readByColumn(right, o.col));
                if (o.asc || r == 0) {
                    return r;
                }
                return r > 0 ? -1 : 1;
            };
            cmp = cmp == null ? next : cmp.thenComparing(next);
        }
        if (cmp != null) {
            list.sort(cmp);
        }
    }

    private int cmpNullsLast(Object a, Object b) {
        if (a == null) {
            return b == null ? 0 : 1;
        }
        if (b == null) {
            return -1;
        }
        return cmp(a, b);
    }

    private List<Object> project(List<T> entities, List<String> cols) {
        List<Object> out = new ArrayList<>(entities.size());
        for (T e : entities) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String c : cols) {
                row.put(c, readByColumn(e, c));
            }
            out.add(row);
        }
        return out;
    }

    private Map<String, String> field2ColOf(Class<?> clazz) {
        if (clazz == null) {
            return new LinkedHashMap<>();
        }
        if (clazz == persistentClass && field2ColMap != null) {
            return field2ColMap;
        }
        List<Field> all = MyBeanUtils.getAllPersistentFields(clazz);
        Map<String, String> out = new LinkedHashMap<>();
        for (Field f : all) {
            DsField ann = f.getAnnotation(DsField.class);
            if (ann == null) {
                continue;
            }
            String col = ann.name();
            if (col == null || col.isBlank()) {
                col = f.getName();
            }
            out.put(f.getName(), col);
        }
        return out;
    }

    private Map<String, Field> fieldMapOf(Class<?> clazz) {
        if (clazz == null) {
            return new LinkedHashMap<>();
        }
        if (clazz == persistentClass && fieldsMap != null) {
            return fieldsMap;
        }
        List<Field> all = MyBeanUtils.getAllPersistentFields(clazz);
        Map<String, Field> out = new LinkedHashMap<>();
        for (Field f : all) {
            DsField ann = f.getAnnotation(DsField.class);
            if (ann == null) {
                continue;
            }
            out.put(f.getName(), f);
        }
        return out;
    }

    // ====================== P17 T4 Composite GroupBy Aggregation ======================

    static final class CompositeAggResult {
        final List<Object> key;
        final Map<String, Number> values;
        CompositeAggResult(List<Object> key, Map<String, Number> values) {
            this.key = key; this.values = values;
        }
    }

    /** package-private T4 GROUP BY 流式 聚合，按 复合 索引 前 k 列 前缀 分 bucket。 */
    <X extends DsTableAdapter> List<CompositeAggResult> aggregateByComposite(
            Class<X> clz,
            String ciName,
            String[] groupByCols,
            String[] aggTypes,
            String[] aggCols,
            QueryWrapper<X> filter,
            java.util.Collection<Long> whitelist
    ) throws Exception {
        if (clz == null || ciName == null || groupByCols == null || groupByCols.length == 0)
            throw new IllegalArgumentException("illegal aggregate args");
        if (aggTypes == null || aggCols == null || aggTypes.length != aggCols.length)
            throw new IllegalArgumentException("aggregation arrays mismatched");
        ensureInit();
        CompositeIndexInfo bestCi = null;
        for (CompositeIndexInfo ci : compositeIndexInfo) {
            if (ciName.equals(ci.name)) { bestCi = ci; break; }
        }
        if (bestCi == null) throw new IllegalArgumentException("composite index not found: " + ciName);
        int k = groupByCols.length;
        if (k > bestCi.columns.size()) throw new IllegalArgumentException("groupBy cols exceed ci length");
        for (int i = 0; i < k; i++) {
            if (!groupByCols[i].equals(bestCi.columns.get(i).columnName)) {
                throw new IllegalArgumentException(
                        "groupBy cols must match ci prefix order at col[" + i + "]: " +
                                groupByCols[i] + " vs " + bestCi.columns.get(i).columnName);
            }
        }
        java.util.Set<Long> wlSet = (whitelist == null || whitelist.isEmpty()) ? null
                : (whitelist instanceof java.util.Set) ? (java.util.Set<Long>) whitelist
                : new java.util.HashSet<>(whitelist);
        @SuppressWarnings("unchecked")
        QueryWrapper<T> fw = (QueryWrapper<T>) filter;
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (wlSet == null) {
            Iterable<Long> idsIt = candidateIdsForQuery(fw);
            if (idsIt == null) {
                java.util.Iterator<Long> it = primaryIdIterator();
                while (it.hasNext()) { Long id = it.next(); if (id != null && id != 0L) ids.add(id); }
            } else {
                for (Long id : idsIt) if (id != null && id != 0L) ids.add(id);
            }
        } else {
            for (Long id : wlSet) if (id != null && id != 0L) ids.add(id);
        }
        java.util.TreeMap<byte[], java.util.List<Long>> localMap =
                new java.util.TreeMap<>(COMPOSITE_KEY_UNSIGNED);
        Object[] buf = new Object[bestCi.columns.size()];
        for (Long id : ids) {
            if (id == null || id == 0L) continue;
            T e = db.getTable(persistentClass, id, Long.MAX_VALUE);
            if (e == null) continue;
            if (wlSet == null && fw != null && !matchesAll(fw, e)) continue;
            for (int i = 0; i < buf.length; i++) {
                buf[i] = readByColumn(e, bestCi.columns.get(i).columnName);
            }
            byte[] key = encodeCompositeKey(bestCi, buf);
            localMap.computeIfAbsent(key, x -> new java.util.ArrayList<>(2)).add(id);
        }
        int prefixBytes = 0;
        for (int i = 0; i < k; i++) prefixBytes += colBytes(bestCi.columns.get(i));
        // 预 解 析 目标 字段
        Map<String, Field> fieldMap = new HashMap<>();
        for (String ac : aggCols) {
            if (ac == null) continue;
            Field f = clz.getField(ac);
            f.setAccessible(true);
            fieldMap.put(ac, f);
        }
        java.util.List<CompositeAggResult> out = new java.util.ArrayList<>();
        byte[] curPrefix = null;
        java.util.List<Long> curBucketRowIds = null;
        for (Map.Entry<byte[], java.util.List<Long>> en : localMap.entrySet()) {
            byte[] k0 = en.getKey();
            byte[] p = Arrays.copyOf(k0, prefixBytes);
            boolean same = curPrefix != null && Arrays.equals(curPrefix, p);
            if (!same) {
                if (curBucketRowIds != null && !curBucketRowIds.isEmpty()) {
                    out.add(flushGroup(curPrefix, bestCi, k, curBucketRowIds,
                            aggTypes, aggCols, fieldMap));
                }
                curPrefix = p;
                curBucketRowIds = new java.util.ArrayList<>(en.getValue().size());
            }
            curBucketRowIds.addAll(en.getValue());
        }
        if (curBucketRowIds != null && !curBucketRowIds.isEmpty()) {
            out.add(flushGroup(curPrefix, bestCi, k, curBucketRowIds,
                    aggTypes, aggCols, fieldMap));
        }
        return out;
    }

    private CompositeAggResult flushGroup(
            byte[] prefixBytes, CompositeIndexInfo ci, int k, List<Long> rowIds,
            String[] aggTypes, String[] aggCols, Map<String, Field> fieldMap
    ) throws Exception {
        Object[] keyValues = decodeCompositeKeyFirstKCols(
                Arrays.copyOf(prefixBytes, fullKeyLen(ci)), k, ci);
        List<Object> key = new ArrayList<>(k);
        for (int i = 0; i < k; i++) key.add(keyValues[i]);
        Map<String, Number> values = new HashMap<>();
        // 先 放 COUNT(*) = rowIds.size
        long n = rowIds.size();
        // 各 agg
        boolean needLoad = false;
        for (String t : aggTypes) {
            if (!"COUNT".equals(t)) { needLoad = true; break; }
        }
        // load 实 体
        List<Object> entities = null;
        if (needLoad) {
            entities = new ArrayList<>((int) Math.min(n, Integer.MAX_VALUE));
            for (Long id : rowIds) entities.add(getById(id));
        }
        for (int i = 0; i < aggTypes.length; i++) {
            String type = aggTypes[i];
            String col = aggCols[i];
            String mapKey = "COUNT".equals(type) ? "COUNT:*" : (type + ":" + col);
            if ("COUNT".equals(type)) {
                values.put(mapKey, n);
            } else if ("SUM".equals(type)) {
                java.math.BigInteger sum = java.math.BigInteger.ZERO;
                Field f = fieldMap.get(col);
                for (Object e : entities) {
                    Number v = (Number) f.get(e);
                    if (v != null) sum = sum.add(java.math.BigInteger.valueOf(v.longValue()));
                }
                values.put(mapKey, sum);
            } else if ("MIN".equals(type)) {
                Long min = null;
                Field f = fieldMap.get(col);
                for (Object e : entities) {
                    Number v = (Number) f.get(e);
                    if (v == null) continue;
                    long lv = v.longValue();
                    if (min == null || lv < min) min = lv;
                }
                values.put(mapKey, min == null ? 0 : min);
            } else if ("MAX".equals(type)) {
                Long max = null;
                Field f = fieldMap.get(col);
                for (Object e : entities) {
                    Number v = (Number) f.get(e);
                    if (v == null) continue;
                    long lv = v.longValue();
                    if (max == null || lv > max) max = lv;
                }
                values.put(mapKey, max == null ? 0 : max);
            } else {
                throw new IllegalArgumentException("unsupported aggregation type: " + type);
            }
        }
        return new CompositeAggResult(key, values);
    }

    private int fullKeyLen(CompositeIndexInfo ci) {
        int s = 0;
        for (IndexedColInfo c : ci.columns) s += colBytes(c);
        return s;
    }

    /**
     * P28 FR-1 Facade 单调不减：将私有的 candidateIdsForQuery 包装为公开 List<Long>，便于
     * DsHybridQueryClient 作为 ORM 过滤候选 id 提供者 1:1 对齐 listEntities/findList 顺序。
     *
     * <p>与 findList / listEntities 一致：candidateIdsForQuery 仅保证索引候选范围；
     * 每个 id 仍需 getTable 后通过 matchesAll(wrapper, entity) 二次后过滤，保证与 findList 结果 1:1。
     *
     * @param wrapper 可为 null（等价空条件，返回全表 id 列表）
     * @return 候选 rowId 列表（顺序与 listEntities 迭代 post-filter 顺序 100% 全等）
     * @since P28
     */
    public List<Long> listIds(QueryWrapper<T> wrapper) {
        ensureInit();
        List<Long> out = new ArrayList<>();
        java.util.Iterator<Long> idIt;
        Iterable<Long> it = candidateIdsForQuery(wrapper);
        if (it == null) idIt = primaryIdIterator();
        else idIt = it.iterator();
        boolean emptyWrapper = (wrapper == null || isEmptyWrapper(wrapper));
        long ts = readTimestamp;
        while (idIt.hasNext()) {
            Long id = idIt.next();
            if (id == null || id == 0L) continue;
            if (!emptyWrapper) {
                T e = db.getTable(persistentClass, id, ts);
                if (e == null || !matchesAll(wrapper, e)) continue;
            }
            out.add(id);
        }
        return out;
    }

}
