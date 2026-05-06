/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.q3lives.ds.database;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.annotation.DsManyToMany;
import com.q3lives.ds.annotation.DsMapField;
import com.q3lives.ds.annotation.DsOneToMany;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.database.schema.EntityIndexUtil;
import com.q3lives.ds.util.DsPathUtil;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.yaml.snakeyaml.Yaml;

/**
 *
 * @author Administrator
 */
public class DsDatabaseLocal {
    
    private static final String DEFAULT_SYSTEM_YAML_NAME = "SystemConfig.yaml";
    private static final String SYS_PROP_SYSTEM_YAML = "p2p.system.yaml";
    private static final String SYS_PROP_DB_HOME = "p2p.db.home";

    private final File root;
    private final DsFixedBucketStore bucketStore;
    private final ConcurrentHashMap<Class<? extends DsTableAdapter>, TableMeta<?>> tableMetaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DsHashMap> relationMapCache = new ConcurrentHashMap<>();
    
    public DsDatabaseLocal(File root) {
        this.root = Objects.requireNonNull(root, "root cannot be null");
        if (!this.root.exists()) {
            this.root.mkdirs();
        }
        this.bucketStore = new DsFixedBucketStore(this.root.getAbsolutePath());
    }
    
    public File getRoot() {
        return root;
    }
    
    public static DsDatabaseLocal load() {
        File home = resolveDbHomeFromYamlOrSystem();
        return new DsDatabaseLocal(home);
    }
    
    private static File resolveDbHomeFromYamlOrSystem() {
        String home = System.getProperty(SYS_PROP_DB_HOME);
        if (home != null && !home.isBlank()) {
            return new File(home).getAbsoluteFile();
        }
        
        String yamlPath = System.getProperty(SYS_PROP_SYSTEM_YAML);
        File yamlFile = null;
        if (yamlPath != null && !yamlPath.isBlank()) {
            yamlFile = new File(yamlPath).getAbsoluteFile();
        } else {
            File local = new File(System.getProperty("user.dir", "."), DEFAULT_SYSTEM_YAML_NAME).getAbsoluteFile();
            if (local.isFile()) {
                yamlFile = local;
            }
        }
        
        if (yamlFile == null || !yamlFile.isFile()) {
            return new File(System.getProperty("user.dir", "."), "dsdb").getAbsoluteFile();
        }
        
        try (InputStream in = new FileInputStream(yamlFile)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map<?, ?> map)) {
                return new File(System.getProperty("user.dir", "."), "dsdb").getAbsoluteFile();
            }
            Object v = map.get("DbHome");
            if (v == null) {
                v = map.get("dbHome");
            }
            if (!(v instanceof String s) || s.isBlank()) {
                return new File(System.getProperty("user.dir", "."), "dsdb").getAbsoluteFile();
            }
            File f = new File(s);
            if (f.isAbsolute()) {
                return f.getAbsoluteFile();
            }
            return new File(yamlFile.getParentFile(), s).getAbsoluteFile();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    private TableMeta<? extends DsTableAdapter> metaOf(Class<? extends DsTableAdapter> clazz) {
        return tableMetaCache.computeIfAbsent(clazz, this::createMeta);
    }
    
    private TableMeta<? extends DsTableAdapter> createMeta(Class<? extends DsTableAdapter> clazz) {
        String space = clazz.getName();
        EntityIndexUtil.IndexDef index = EntityIndexUtil.indexOf(root, clazz);
        int sample = sampleRowLength(clazz);
        if (sample != index.rowLength) {
            throw new IllegalStateException("row length mismatch: expected=" + index.rowLength + ", actual=" + sample);
        }
        return new TableMeta<>(clazz, space, index.rowType, index.rowLength);
    }
    
    private static int sampleRowLength(Class<? extends DsTableAdapter> clazz) {
        DsTableAdapter sample = newInstance(clazz);
        ByteBuffer buf = sample.toBytes();
        return buf.remaining();
    }
    
    private static <T extends DsTableAdapter> T newInstance(Class<T> clazz) {
        try {
            Constructor<T> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("实体必须包含无参构造: " + clazz.getName(), e);
        }
    }
    
    public <T extends DsTableAdapter> long putEntity(T value, boolean assignIdIfMissing) throws IOException {
        Objects.requireNonNull(value, "value cannot be null");
        @SuppressWarnings("unchecked")
        TableMeta<T> meta = (TableMeta<T>) metaOf(value.getClass());
        
        Long id = value.getId();
        if (assignIdIfMissing && (id == null || id == 0L)) {
            long newId = bucketStore.getNewId(meta.space, meta.type, meta.rowLength);
            value.setId(newId);
            id = newId;
        }
        if (id == null || id == 0L) {
            throw new IllegalArgumentException("entity id is missing");
        }
        
        ByteBuffer buf = value.toBytes();
        if (buf.remaining() != meta.rowLength) {
            throw new IllegalStateException("row length mismatch: expected=" + meta.rowLength + ", actual=" + buf.remaining());
        }
        byte[] bytes = buf.array();
        bucketStore.update(meta.space, meta.type, id, bytes, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        return id;
    }
    
    public <T extends DsTableAdapter> T getTable(Class<T> clazz, long id) {
        Objects.requireNonNull(clazz, "clazz cannot be null");
        if (id == 0L) {
            throw new IllegalArgumentException("id is 0");
        }
        @SuppressWarnings("unchecked")
        TableMeta<T> meta = (TableMeta<T>) metaOf(clazz);
        try {
            byte[] bytes = bucketStore.get(meta.space, meta.type, id, 0, meta.rowLength);
            T obj = newInstance(clazz);
            obj.load(ByteBuffer.wrap(bytes));
            return obj;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    public long putTable(DsTableAdapter value, boolean withRelations) throws IOException {
        long id = putEntity(value, true);
        if (!withRelations) {
            return id;
        }
        persistRelations(value);
        return id;
    }
    
    public <T extends DsTableAdapter> T getTable(Class<T> clazz, long id, boolean withRelations) {
        T obj = getTable(clazz, id);
        if (!withRelations) {
            return obj;
        }
        loadRelations(obj);
        return obj;
    }

    public <T extends DsTableAdapter> boolean removeTable(Class<T> clazz, long id, boolean withRelations) throws IOException {
        Objects.requireNonNull(clazz, "clazz cannot be null");
        if (id == 0L) {
            throw new IllegalArgumentException("id is 0");
        }
        @SuppressWarnings("unchecked")
        TableMeta<T> meta = (TableMeta<T>) metaOf(clazz);
        if (withRelations) {
            removeRelations(clazz, id);
        }
        bucketStore.remove(meta.space, meta.type, id);
        return true;
    }

    private void removeRelations(Class<?> ownerClass, long ownerId) throws IOException {
        Field[] fields = ownerClass.getDeclaredFields();
        for (Field f : fields) {
            if (f.isAnnotationPresent(DsOneToOne.class)) {
                relationMap(ownerClass, f.getName(), "one_to_one").remove(ownerId);
                continue;
            }
            if (f.isAnnotationPresent(DsOneToMany.class)) {
                removeVarRelation(ownerClass, f.getName(), "one_to_many", "list", ownerId);
                continue;
            }
            if (f.isAnnotationPresent(DsManyToMany.class)) {
                removeVarRelation(ownerClass, f.getName(), "many_to_many", "list", ownerId);
                continue;
            }
            if (f.isAnnotationPresent(DsMapField.class)) {
                removeVarRelation(ownerClass, f.getName(), "map", "pairs", ownerId);
            }
        }
    }

    private void removeVarRelation(Class<?> ownerClass, String fieldName, String kind, String type, long ownerId) throws IOException {
        DsHashMap map = relationMap(ownerClass, fieldName, kind);
        long stored = map.getOrDefault(ownerId, 0L);
        map.remove(ownerId);
        if (stored != 0L) {
            bucketStore.remove(relSpace(ownerClass, fieldName, kind), type, stored);
        }
    }
    
    public long putTableWithRelations(DsTableAdapter value) throws IOException {
        return putTable(value, true);
    }
    
    public <T extends DsTableAdapter> T getTableWithRelations(Class<T> clazz, long id) {
        return getTable(clazz, id, true);
    }
    
    private void persistRelations(DsTableAdapter owner) throws IOException {
        if (owner == null || owner.getId() == null || owner.getId() == 0L) {
            return;
        }
        Field[] fields = owner.getClass().getDeclaredFields();
        for (Field f : fields) {
            if (f.isAnnotationPresent(DsOneToOne.class)) {
                persistOneToOne(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsOneToMany.class)) {
                persistOneToMany(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsManyToMany.class)) {
                persistManyToMany(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsMapField.class)) {
                persistMapField(owner, f);
            }
        }
    }
    
    private void loadRelations(DsTableAdapter owner) {
        if (owner == null || owner.getId() == null || owner.getId() == 0L) {
            return;
        }
        Field[] fields = owner.getClass().getDeclaredFields();
        for (Field f : fields) {
            if (f.isAnnotationPresent(DsOneToOne.class)) {
                loadOneToOne(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsOneToMany.class)) {
                loadOneToMany(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsManyToMany.class)) {
                loadManyToMany(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsMapField.class)) {
                loadMapField(owner, f);
            }
        }
    }
    
    private void persistOneToOne(DsTableAdapter owner, Field field) throws IOException {
        field.setAccessible(true);
        Object v;
        try {
            v = field.get(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        DsHashMap map = relationMap(owner.getClass(), field.getName(), "one_to_one");
        if (v == null) {
            map.remove(owner.getId());
            return;
        }
        if (!(v instanceof DsTableAdapter join)) {
            throw new IllegalArgumentException("DsOneToOne field must be DsTableAdapter: " + owner.getClass().getName() + "." + field.getName());
        }
        long joinId = putEntity(join, true);
        map.put(owner.getId().longValue(), joinId);
    }
    
    private void loadOneToOne(DsTableAdapter owner, Field field) {
        DsHashMap map = relationMap(owner.getClass(), field.getName(), "one_to_one");
        Long joinId = map.get(owner.getId());
        if (joinId == null || joinId == 0L) {
            return;
        }
        Class<?> joinClass = field.getType();
        if (!DsTableAdapter.class.isAssignableFrom(joinClass)) {
            throw new IllegalArgumentException("DsOneToOne field type must extend DsTableAdapter: " + owner.getClass().getName() + "." + field.getName());
        }
        @SuppressWarnings("unchecked")
        DsTableAdapter join = getTable((Class<? extends DsTableAdapter>) joinClass, joinId);
        try {
            field.setAccessible(true);
            field.set(owner, join);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    
    private void persistOneToMany(DsTableAdapter owner, Field field) throws IOException {
        DsOneToMany ann = field.getAnnotation(DsOneToMany.class);
        persistIdCollection(owner, field, ann.joinClass(), "one_to_many");
    }
    
    private void loadOneToMany(DsTableAdapter owner, Field field) {
        DsOneToMany ann = field.getAnnotation(DsOneToMany.class);
        loadIdCollection(owner, field, ann.joinClass(), "one_to_many");
    }
    
    private void persistManyToMany(DsTableAdapter owner, Field field) throws IOException {
        DsManyToMany ann = field.getAnnotation(DsManyToMany.class);
        persistIdCollection(owner, field, ann.joinClass(), "many_to_many");
    }
    
    private void loadManyToMany(DsTableAdapter owner, Field field) {
        DsManyToMany ann = field.getAnnotation(DsManyToMany.class);
        loadIdCollection(owner, field, ann.joinClass(), "many_to_many");
    }
    
    private void persistIdCollection(DsTableAdapter owner, Field field, Class<? extends DsTableAdapter> joinClass, String kind) throws IOException {
        field.setAccessible(true);
        Object v;
        try {
            v = field.get(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        DsHashMap map = relationMap(owner.getClass(), field.getName(), kind);
        if (v == null) {
            map.remove(owner.getId());
            return;
        }
        if (!(v instanceof Collection<?> col)) {
            throw new IllegalArgumentException("relation field must be Collection: " + owner.getClass().getName() + "." + field.getName());
        }
        if (col.isEmpty()) {
            map.remove(owner.getId());
            return;
        }
        
        long[] ids = new long[col.size()];
        int i = 0;
        for (Object item : col) {
            if (!(item instanceof DsTableAdapter join)) {
                throw new IllegalArgumentException("collection element must be DsTableAdapter: " + owner.getClass().getName() + "." + field.getName());
            }
            if (!joinClass.isInstance(join)) {
                throw new IllegalArgumentException("collection element type mismatch: expected=" + joinClass.getName());
            }
            ids[i++] = putEntity(join, true);
        }
        byte[] payload = encodeIdList(ids);
        long old = map.getOrDefault(owner.getId(), 0L);
        long stored = storeVarBytes(relSpace(owner.getClass(), field.getName(), kind), "list", old, payload);
        map.put(owner.getId().longValue(), stored);
    }
    
    private void loadIdCollection(DsTableAdapter owner, Field field, Class<? extends DsTableAdapter> joinClass, String kind) {
        DsHashMap map = relationMap(owner.getClass(), field.getName(), kind);
        Long stored = map.get(owner.getId());
        if (stored == null || stored == 0L) {
            return;
        }
        byte[] payload = loadVarBytes(relSpace(owner.getClass(), field.getName(), kind), "list", stored);
        long[] ids = decodeIdList(payload);
        if (ids.length == 0) {
            return;
        }
        List<DsTableAdapter> out = new ArrayList<>(ids.length);
        for (long id : ids) {
            out.add(getTable(joinClass, id));
        }
        try {
            field.setAccessible(true);
            if (List.class.isAssignableFrom(field.getType())) {
                field.set(owner, out);
                return;
            }
            if (Collection.class.isAssignableFrom(field.getType())) {
                field.set(owner, out);
                return;
            }
            throw new IllegalArgumentException("relation field must be List/Collection: " + owner.getClass().getName() + "." + field.getName());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    
    private void persistMapField(DsTableAdapter owner, Field field) throws IOException {
        DsMapField ann = field.getAnnotation(DsMapField.class);
        field.setAccessible(true);
        Object v;
        try {
            v = field.get(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        DsHashMap map = relationMap(owner.getClass(), field.getName(), "map");
        if (v == null) {
            map.remove(owner.getId());
            return;
        }
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("DsMapField must be Map: " + owner.getClass().getName() + "." + field.getName());
        }
        if (m.isEmpty()) {
            map.remove(owner.getId());
            return;
        }
        
        long[] pairs = new long[m.size() * 2];
        int idx = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof DsTableAdapter k)) {
                throw new IllegalArgumentException("map key must be DsTableAdapter");
            }
            if (!(e.getValue() instanceof DsTableAdapter val)) {
                throw new IllegalArgumentException("map value must be DsTableAdapter");
            }
            if (!ann.keyClass().isInstance(k)) {
                throw new IllegalArgumentException("map key type mismatch: expected=" + ann.keyClass().getName());
            }
            if (!ann.valueClass().isInstance(val)) {
                throw new IllegalArgumentException("map value type mismatch: expected=" + ann.valueClass().getName());
            }
            pairs[idx++] = putEntity(k, true);
            pairs[idx++] = putEntity(val, true);
        }
        byte[] payload = encodeIdPairs(pairs);
        long old = map.getOrDefault(owner.getId(), 0L);
        long stored = storeVarBytes(relSpace(owner.getClass(), field.getName(), "map"), "pairs", old, payload);
        map.put(owner.getId().longValue(), stored);
    }
    
    private void loadMapField(DsTableAdapter owner, Field field) {
        DsMapField ann = field.getAnnotation(DsMapField.class);
        DsHashMap map = relationMap(owner.getClass(), field.getName(), "map");
        Long stored = map.get(owner.getId());
        if (stored == null || stored == 0L) {
            return;
        }
        byte[] payload = loadVarBytes(relSpace(owner.getClass(), field.getName(), "map"), "pairs", stored);
        long[] pairs = decodeIdPairs(payload);
        if (pairs.length == 0) {
            return;
        }
        Map<DsTableAdapter, DsTableAdapter> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            DsTableAdapter k = getTable(ann.keyClass(), pairs[i]);
            DsTableAdapter v = getTable(ann.valueClass(), pairs[i + 1]);
            out.put(k, v);
        }
        try {
            field.setAccessible(true);
            field.set(owner, out);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    
    private DsHashMap relationMap(Class<?> ownerClass, String fieldName, String kind) {
        DsPathUtil.validateSegment(fieldName, "fieldName");
        DsPathUtil.validateSegment(kind, "relation kind");
        String key = ownerClass.getName() + "#" + fieldName + "#" + kind;
        return relationMapCache.computeIfAbsent(key, k -> {
            File f = relationMapFile(ownerClass, fieldName, kind);
            return new DsHashMap(f);
        });
    }
    
    private File relationMapFile(Class<?> ownerClass, String fieldName, String kind) {
        String spacePath = DsPathUtil.dottedToLinuxPath(ownerClass.getName(), "table");
        String relRoot = new File(root, "relations").getAbsolutePath().replace('\\', '/');
        String full = relRoot + "/" + spacePath + "/" + kind;
        File dir = new File(full);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, fieldName + ".map");
    }
    
    private static String relSpace(Class<?> ownerClass, String fieldName, String kind) {
        return ownerClass.getName() + "." + kind + "." + fieldName;
    }
    
    private long storeVarBytes(String space, String type, long oldId, byte[] payload) throws IOException {
        if (payload == null) {
            payload = new byte[0];
        }
        byte[] data = wrapVarPayload(payload);
        if (oldId != 0L) {
            return bucketStore.update(space, type, oldId, data, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        }
        long id = bucketStore.getNewId(space, type, data.length);
        bucketStore.update(space, type, id, data, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        return id;
    }
    
    private byte[] loadVarBytes(String space, String type, long id) {
        int unit = bucketUnitSize(id);
        try {
            byte[] raw = bucketStore.get(space, type, id, 0, unit);
            return unwrapVarPayload(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    private static int bucketUnitSize(long id) {
        int power = (int) ((id >>> DsFixedBucketStore.ID_OFFSET_BITS) & 0xFF);
        return 1 << power;
    }
    
    private static byte[] wrapVarPayload(byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(4 + payload.length);
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }
    
    private static byte[] unwrapVarPayload(byte[] raw) {
        if (raw == null || raw.length < 4) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int len = buf.getInt();
        if (len <= 0) {
            return new byte[0];
        }
        if (len > raw.length - 4) {
            throw new IllegalStateException("corrupted var payload length");
        }
        byte[] out = new byte[len];
        buf.get(out);
        return out;
    }
    
    private static byte[] encodeIdList(long[] ids) {
        ByteBuffer buf = ByteBuffer.allocate(4 + ids.length * 8);
        buf.putInt(ids.length);
        for (long id : ids) {
            buf.putLong(id);
        }
        return buf.array();
    }
    
    private static long[] decodeIdList(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return new long[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int n = buf.getInt();
        if (n <= 0) {
            return new long[0];
        }
        if (payload.length != 4 + n * 8) {
            throw new IllegalStateException("id list payload size mismatch");
        }
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            ids[i] = buf.getLong();
        }
        return ids;
    }
    
    private static byte[] encodeIdPairs(long[] pairs) {
        if ((pairs.length & 1) != 0) {
            throw new IllegalArgumentException("pairs length must be even");
        }
        ByteBuffer buf = ByteBuffer.allocate(4 + pairs.length * 8);
        buf.putInt(pairs.length / 2);
        for (long id : pairs) {
            buf.putLong(id);
        }
        return buf.array();
    }
    
    private static long[] decodeIdPairs(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return new long[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int n = buf.getInt();
        if (n <= 0) {
            return new long[0];
        }
        int expect = 4 + n * 16;
        if (payload.length != expect) {
            throw new IllegalStateException("pairs payload size mismatch");
        }
        long[] out = new long[n * 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getLong();
        }
        return out;
    }
    
    private static final class TableMeta<T extends DsTableAdapter> {
        final Class<T> clazz;
        final String space;
        final String type;
        final int rowLength;
        
        TableMeta(Class<T> clazz, String space, String type, int rowLength) {
            this.clazz = clazz;
            this.space = space;
            this.type = type;
            this.rowLength = rowLength;
        }
    }
    
    /**
     * 存储DsTableAdapter
     * @param value
     * @return
     * @throws IOException 
     */
    public Long putTable(DsTableAdapter value) throws IOException {
        return putTable(value, false);
       
    }
    
    /**
     * 装载DsTableAdapter
     * @param key
     * @return 
     */
    public DsTableAdapter getTable(long key) {
        throw new UnsupportedOperationException("use getTable(Class, long) because id alone cannot determine table class");
    }
    
    /**
     * 存储DsTableAdapter内部一对一字段
     * @param table
     * @param field
     * @return
     * @throws IOException 
     */
    public Long putTable(DsTableAdapter table,DsOneToOne field) throws IOException {
        // TODO file(root,field.getClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter value)
        // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap
        // TODO 存储逻辑: table.getId() -> key : field.joinProp() -> valueId  DsHashMap  valueId==null?新增:更新  调用DsFixedBucketStore -> 存储value.toBytes() 返回64位valueId
        //return add(key);
        return null;
       
    }
    
    /**
     * 装载DsTableAdapter内部一对一字段
     * @param table
     * @param field
     * @return 
     */
    public DsTableAdapter getTable(DsTableAdapter table,DsOneToOne field) {
        try {
            // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap
            // TODO table.getId() -> key : field.joinProp() -> valueId  DsHashMap
            // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long key)
            //TODO 调用DsFixedBucketStore -> load(ByteBuffer data)
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }
    
    /**
     * 存储DsTableAdapter内部一对多字段 List/Set
     * @param table
     * @param field
     * @return
     * @throws IOException 
     */
    public Long putTable(DsTableAdapter table,DsOneToMany field) throws IOException {
        // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter value)
        // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap 存储一对多Ids.
        // TODO 存储逻辑: table.getId() -> key : field.joinProp() -> valueIds  DsHashMap  valueId==null?新增:更新  调用DsFixedBucketStore -> 存储 List/Set(field.joinProp() -> valueIds) 返回64位valueId
        //return add(key);
        return null;
       
    }
    
    /**
     * 装载DsTableAdapter内部一对多字段 List/Set
     * @param table
     * @param field
     * @return 
     */
    public DsTableAdapter getTable(DsTableAdapter table,DsOneToMany field) {
        try {
            // TODO file(root,field.joinClass().getName() +"_"+field.name -> [子目录+文件名]) -> DsHashMap 获取一对多Ids.
            // TODO table.getId() -> key : field.joinProp()  -> valueIds  DsHashMap
            // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long key)
            //TODO 调用DsFixedBucketStore -> load(ByteBuffer data)
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }
    
     /**
     * 存储DsTableAdapter内部多对多字段-List,Set
     * @param table
     * @param field
     * @return
     * @throws IOException 
     */
    public Long putTable(DsTableAdapter table,DsManyToMany field) throws IOException {
        // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter value)
        // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap 存储一对多Ids.
        // TODO 存储逻辑: table.getId() -> key : field.id() -> valueIds  DsHashMap  valueId==null?新增:更新  调用DsFixedBucketStore -> 存储 List/Set(field.id() -> valueIds) 返回64位valueId
        //return add(key);
        return null;
       
    }
    
    /**
     * 装载DsTableAdapter内部多对多字段-List,Set
     * @param table
     * @param field
     * @return 
     */
    public DsTableAdapter getTable(DsTableAdapter table,DsManyToMany field) {
        try {
            // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap
            // TODO table.getId() -> key : field.joinProp() -> valueIds  DsHashMap
            // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long key)
            //TODO 调用DsFixedBucketStore -> load(ByteBuffer data)
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }
    
    /**
     * 存储DsTableAdapter内部多对多字段-List,Set
     * @param table
     * @param field
     * @return
     * @throws IOException 
     */
    public Long putTable(DsTableAdapter table,DsMapField field) throws IOException {
        // TODO file(root,field.keyClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter key)
       // TODO file(root,field.valueClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter value)
        // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap 存储List{key,value}
        // TODO 存储逻辑: table.getId() -> key : field.id() -> valueIds  DsHashMap  valueId==null?新增:更新  调用DsFixedBucketStore -> 存储 List{key,value} 返回64位valueId
        //return add(key);
        return null;
       
    }
    
    /**
     * 装载DsTableAdapter内部多对多字段-List,Set
     * @param table
     * @param field
     * @return 
     */
    public DsTableAdapter getTable(DsTableAdapter table,DsMapField field) {
        try {
            // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap 获取List{key,value}
            // TODO table.getId() -> key : valueId -> List{key,value}  DsHashMap
           // TODO file(root,field.keyClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long keyId)
       // TODO file(root,field.valueClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long valueId)
            //TODO 调用DsFixedBucketStore -> load(ByteBuffer data)
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }
    
}
