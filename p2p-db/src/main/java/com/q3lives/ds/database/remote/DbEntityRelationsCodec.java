package com.q3lives.ds.database.remote;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.annotation.DsManyToMany;
import com.q3lives.ds.annotation.DsMapField;
import com.q3lives.ds.annotation.DsOneToMany;
import com.q3lives.ds.annotation.DsOneToOne;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.p2p.model.DbEntityBlob;
import javax.net.p2p.model.DbEntityRelationField;
import javax.net.p2p.model.DbEntityRelationsPayload;
import javax.net.p2p.utils.SerializationUtil;

public final class DbEntityRelationsCodec {
    private DbEntityRelationsCodec() {
    }
    
    public static byte[] encode(DsTableAdapter entity) {
        if (entity == null) {
            return null;
        }
        Field[] declared = entity.getClass().getDeclaredFields();
        IdentityHashMap<Object, Integer> dedup = new IdentityHashMap<>();
        List<DbEntityBlob> blobs = new ArrayList<>();
        List<DbEntityRelationField> fields = new ArrayList<>();
        
        for (Field f : declared) {
            DbEntityRelationField rf = encodeField(entity, f, dedup, blobs);
            if (rf != null) {
                fields.add(rf);
            }
        }
        if (fields.isEmpty()) {
            return null;
        }
        return SerializationUtil.serialize(new DbEntityRelationsPayload(blobs, fields));
    }
    
    private static DbEntityRelationField encodeField(
        DsTableAdapter owner,
        Field field,
        IdentityHashMap<Object, Integer> dedup,
        List<DbEntityBlob> blobs
    ) {
        boolean isOneToOne = field.isAnnotationPresent(DsOneToOne.class);
        boolean isOneToMany = field.isAnnotationPresent(DsOneToMany.class);
        boolean isManyToMany = field.isAnnotationPresent(DsManyToMany.class);
        boolean isMap = field.isAnnotationPresent(DsMapField.class);
        if (!isOneToOne && !isOneToMany && !isManyToMany && !isMap) {
            return null;
        }
        
        field.setAccessible(true);
        Object v;
        try {
            v = field.get(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        if (v == null) {
            return new DbEntityRelationField(field.getName(), kindOf(field), new int[0]);
        }
        
        if (isOneToOne) {
            if (!(v instanceof DsTableAdapter join)) {
                throw new IllegalArgumentException("DsOneToOne field must be DsTableAdapter: " + field.getName());
            }
            return new DbEntityRelationField(field.getName(), "one_to_one", new int[] {indexOf(join, dedup, blobs)});
        }
        
        if (isOneToMany || isManyToMany) {
            if (!(v instanceof Collection<?> col)) {
                throw new IllegalArgumentException("relation field must be Collection: " + field.getName());
            }
            int[] idx = new int[col.size()];
            int i = 0;
            for (Object item : col) {
                if (!(item instanceof DsTableAdapter join)) {
                    throw new IllegalArgumentException("collection element must be DsTableAdapter: " + field.getName());
                }
                idx[i++] = indexOf(join, dedup, blobs);
            }
            return new DbEntityRelationField(field.getName(), kindOf(field), idx);
        }
        
        if (isMap) {
            if (!(v instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("DsMapField must be Map: " + field.getName());
            }
            int[] idx = new int[map.size() * 2];
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof DsTableAdapter k)) {
                    throw new IllegalArgumentException("map key must be DsTableAdapter: " + field.getName());
                }
                if (!(e.getValue() instanceof DsTableAdapter val)) {
                    throw new IllegalArgumentException("map value must be DsTableAdapter: " + field.getName());
                }
                idx[i++] = indexOf(k, dedup, blobs);
                idx[i++] = indexOf(val, dedup, blobs);
            }
            return new DbEntityRelationField(field.getName(), "map", idx);
        }
        
        return null;
    }
    
    private static int indexOf(DsTableAdapter e, IdentityHashMap<Object, Integer> dedup, List<DbEntityBlob> blobs) {
        Integer idx = dedup.get(e);
        if (idx != null) {
            return idx;
        }
        ByteBuffer buf = e.toBytes();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        int newIndex = blobs.size();
        blobs.add(new DbEntityBlob(e.getClass().getName(), bytes));
        dedup.put(e, newIndex);
        return newIndex;
    }
    
    private static String kindOf(Field f) {
        if (f.isAnnotationPresent(DsOneToOne.class)) {
            return "one_to_one";
        }
        if (f.isAnnotationPresent(DsOneToMany.class)) {
            return "one_to_many";
        }
        if (f.isAnnotationPresent(DsManyToMany.class)) {
            return "many_to_many";
        }
        if (f.isAnnotationPresent(DsMapField.class)) {
            return "map";
        }
        return "unknown";
    }
    
    public static void apply(DsTableAdapter entity, byte[] payloadBytes) {
        if (entity == null || payloadBytes == null || payloadBytes.length == 0) {
            return;
        }
        DbEntityRelationsPayload payload = SerializationUtil.deserialize(DbEntityRelationsPayload.class, payloadBytes);
        if (payload == null || payload.entities == null || payload.entities.isEmpty() || payload.fields == null) {
            return;
        }
        
        List<DsTableAdapter> objects = new ArrayList<>(payload.entities.size());
        for (DbEntityBlob blob : payload.entities) {
            objects.add(newEntity(blob));
        }
        
        for (DbEntityRelationField rf : payload.fields) {
            if (rf == null || rf.fieldName == null || rf.fieldName.isBlank()) {
                continue;
            }
            Field f = findField(entity.getClass(), rf.fieldName);
            if (f == null) {
                continue;
            }
            applyField(entity, f, rf, objects);
        }
    }
    
    private static DsTableAdapter newEntity(DbEntityBlob blob) {
        if (blob == null || blob.className == null || blob.className.isBlank() || blob.bytes == null) {
            throw new IllegalArgumentException("invalid relation entity blob");
        }
        try {
            Class<?> raw = Class.forName(blob.className);
            if (!DsTableAdapter.class.isAssignableFrom(raw)) {
                throw new IllegalArgumentException("invalid entity class: " + blob.className);
            }
            @SuppressWarnings("unchecked")
            Class<? extends DsTableAdapter> clazz = (Class<? extends DsTableAdapter>) raw;
            DsTableAdapter entity = clazz.getDeclaredConstructor().newInstance();
            entity.load(ByteBuffer.wrap(blob.bytes));
            return entity;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    private static Field findField(Class<?> clazz, String name) {
        try {
            return clazz.getDeclaredField(name);
        } catch (Exception ignored) {
            return null;
        }
    }
    
    private static void applyField(DsTableAdapter owner, Field field, DbEntityRelationField rf, List<DsTableAdapter> objects) {
        int[] idx = rf.indices == null ? new int[0] : rf.indices;
        
        field.setAccessible(true);
        try {
            if (field.isAnnotationPresent(DsOneToOne.class)) {
                field.set(owner, idx.length == 1 ? objects.get(idx[0]) : null);
                return;
            }
            if (field.isAnnotationPresent(DsOneToMany.class) || field.isAnnotationPresent(DsManyToMany.class)) {
                List<DsTableAdapter> list = new ArrayList<>(idx.length);
                for (int i : idx) {
                    list.add(objects.get(i));
                }
                field.set(owner, list);
                return;
            }
            if (field.isAnnotationPresent(DsMapField.class)) {
                Map<DsTableAdapter, DsTableAdapter> map = new LinkedHashMap<>();
                for (int i = 0; i + 1 < idx.length; i += 2) {
                    map.put(objects.get(idx[i]), objects.get(idx[i + 1]));
                }
                field.set(owner, map);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

