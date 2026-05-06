
package com.q3lives.ds.database.integration;


import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.query.OrderByProp;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.schema.EntityIndexUtil;
import com.q3lives.ds.util.DateUtil;
import com.q3lives.ds.util.MyBeanUtils;
import com.q3lives.ds.util.MyDsDatabaseUtil;
import com.q3lives.ds.util.OrderWrapper;
import com.q3lives.ds.util.StringUtils;
import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.distance.DistanceUtils;
import com.spatial4j.core.io.GeohashUtils;
import com.spatial4j.core.shape.Rectangle;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.collections.CollectionUtils;

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
    private volatile DsHashSet idSet;
    private final Object initLock = new Object();
    
    private volatile Map<String, String> field2ColMap;
    private volatile Map<String, Field> fieldsMap;
    private volatile Map<String, Field> col2FieldMap;
    private volatile List<Field> persistentFields;
    
    public GenericManager() {
        this.db = DsDatabaseLocal.load();
    }
    
    public GenericManager(Class<T> persistentClass) {
        this();
        this.persistentClass = persistentClass;
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

    private void ensureInit() {
        if (idSet != null && field2ColMap != null && fieldsMap != null && col2FieldMap != null && persistentFields != null) {
            return;
        }
        synchronized (initLock) {
            if (idSet != null && field2ColMap != null && fieldsMap != null && col2FieldMap != null && persistentFields != null) {
                return;
            }
            if (persistentClass == null) {
                throw new IllegalStateException("persistentClass is not set");
            }
            EntityIndexUtil.IndexDef index = EntityIndexUtil.indexOf(db.getRoot(), persistentClass);
            idSet = new DsHashSet(index.idsFile);
            initFieldCaches();
        }
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
        ensureInit();
        long v = toLongId(id);
        try {
            db.removeTable(persistentClass, v, true);
            idSet.remove(v);
            return 1;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
       
    }
    
     /**
     * 根据 ID 选择修改
     *
     * @param entity 实体对象
     * @return 
     */
   
    public int updateById(T entity) {
        ensureInit();
        if (entity == null || entity.getId() == null || entity.getId() == 0L) {
            throw new IllegalArgumentException("missing id");
        }
        try {
            db.putTable(entity, true);
            idSet.add(entity.getId());
            return 1;
        } catch (IOException e) {
            throw new RuntimeException(e);
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
        ensureInit();
        long v = toLongId(id);
        return db.getTable(persistentClass, v);
    }
    
    /**
     * TableId 注解存在更新记录，否插入一条记录
     *
     * @param entity 实体对象
     * @return 
     */
   
    public int saveOrUpdate(T entity){
        ensureInit();
        if (entity == null) {
            return 0;
        }
        try {
            long id = db.putTable(entity, true);
            idSet.add(id);
            return 1;
        } catch (IOException e) {
            throw new RuntimeException(e);
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
        ensureInit();
        return idSet.contains(toLongId((Serializable) id));
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

    private int count(QueryWrapper<T> queryWrapper) {
        ensureInit();
        int c = 0;
        for (Long id : idSet) {
            T e = db.getTable(persistentClass, id);
            if (matchesAll(queryWrapper, e)) {
                c++;
            }
        }
        return c;
    }

      private T getOne(QueryWrapper<T> queryWrapper) {
        ensureInit();
        for (Long id : idSet) {
            T e = db.getTable(persistentClass, id);
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
        ensureInit();
        @SuppressWarnings("unchecked")
        QueryWrapper<T> w = (QueryWrapper<T>) wrapper;
        return sliceEntities(w, start, end);
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
        List<T> all = listEntities(wrapper);
        if (start <= 0 && end <= 0) {
            return all;
        }
        int from = Math.max(0, start);
        int to = Math.min(all.size() - 1, end);
        if (to < from || all.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(all.subList(from, to + 1));
    }

    private List<T> listEntities(QueryWrapper<T> wrapper) {
        List<T> out = new ArrayList<>();
        for (Long id : idSet) {
            T e = db.getTable(persistentClass, id);
            if (!matchesAll(wrapper, e)) {
                continue;
            }
            out.add(e);
        }
        sort(out, wrapper);
        return out;
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
        return true;
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
        };
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

}
