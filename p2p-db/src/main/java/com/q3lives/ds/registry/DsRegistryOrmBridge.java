package com.q3lives.ds.registry;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.schema.EntitySchemaUtil;
import com.q3lives.ds.enums.DataType;

public class DsRegistryOrmBridge {

    private final DsRegistry registry;

    public DsRegistryOrmBridge(DsRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry is null");
        this.registry = registry;
    }

    public String registerEntity(Class<? extends DsTableAdapter> clazz, String rootKeyPath) throws IOException {
        if (clazz == null) throw new IllegalArgumentException("clazz is null");
        if (rootKeyPath == null) throw new IllegalArgumentException("rootKeyPath is null");
        EntitySchemaUtil.SchemaDef schema = EntitySchemaUtil.schemaOf(clazz);
        if (schema == null || schema.schemaId == null) {
            throw new IllegalStateException("EntitySchemaUtil.schemaOf(" + clazz.getName() + ") 未返回 schema");
        }
        String base = ("/".equals(rootKeyPath) ? "" : rootKeyPath) + "/" + schema.schemaId + "/" + clazz.getSimpleName();
        String columnsPath = base + "/columns";
        registry.mkdirs(columnsPath);
        registry.mkdirs(base + "/composites");
        registry.mkdirs(base + "/compositeIndexes");
        List<? extends EntitySchemaUtil.ColumnDef> cols = schema.getColumns();
        if (cols != null) {
            for (EntitySchemaUtil.ColumnDef c : cols) {
                String colBase = columnsPath + "/" + c.name;
                registry.mkdirs(colBase);
                String typeKey = colBase + "/type";
                if (!registry.exists(typeKey)) {
                    registry.putString(typeKey, c.typeName, DataType.REG_SZ);
                }
                String lenKey = colBase + "/length";
                if (!registry.exists(lenKey)) {
                    registry.putLong(lenKey, DataType.REG_DWORD, (long) c.length);
                }
                String idxKey = colBase + "/indexed";
                if (!registry.exists(idxKey)) {
                    registry.putLong(idxKey, DataType.REG_DWORD, c.indexed ? 1L : 0L);
                }
                String mvccKey = colBase + "/mvcc";
                if (!registry.exists(mvccKey)) {
                    registry.putLong(mvccKey, DataType.REG_DWORD, c.mvcc ? 1L : 0L);
                }
                String precKey = colBase + "/precision";
                if (!registry.exists(precKey)) {
                    registry.putLong(precKey, DataType.REG_DWORD, (long) c.precision);
                }
                String scaleKey = colBase + "/scale";
                if (!registry.exists(scaleKey)) {
                    registry.putLong(scaleKey, DataType.REG_DWORD, (long) c.scale);
                }
                if (c.defaultValue != null && !registry.exists(colBase + "/default")) {
                    registry.putString(colBase + "/default", c.defaultValue, DataType.REG_SZ);
                }
            }
        }
        String schemaIdLeaf = base + "/.schemaId";
        if (!registry.exists(schemaIdLeaf)) {
            registry.putString(schemaIdLeaf, schema.schemaId, DataType.REG_SZ);
        }
        String rowLenLeaf = base + "/.rowLength";
        if (!registry.exists(rowLenLeaf)) {
            registry.putLong(rowLenLeaf, DataType.REG_DWORD, (long) schema.rowLength);
        }
        String classNameLeaf = base + "/.className";
        if (!registry.exists(classNameLeaf)) {
            registry.putString(classNameLeaf, clazz.getName(), DataType.REG_SZ);
        }
        List<? extends EntitySchemaUtil.CompositeDef> comps = schema.getComposites();
        if (comps != null) {
            for (EntitySchemaUtil.CompositeDef cp : comps) {
                String cpBase = base + "/composites/" + cp.name;
                registry.mkdirs(cpBase);
                String cpGroup = cpBase + "/group";
                if (!registry.exists(cpGroup)) registry.putString(cpGroup, cp.group, DataType.REG_SZ);
                String cpLen = cpBase + "/length";
                if (!registry.exists(cpLen)) registry.putLong(cpLen, DataType.REG_DWORD, (long) cp.length);
            }
        }
        List<? extends EntitySchemaUtil.CompositeIndexDef> cidxs = schema.getCompositeIndexes();
        if (cidxs != null) {
            for (EntitySchemaUtil.CompositeIndexDef ci : cidxs) {
                String ciBase = base + "/compositeIndexes/" + ci.name;
                registry.mkdirs(ciBase);
                List<String> names = new ArrayList<>();
                List<? extends EntitySchemaUtil.ColumnDef> cicols = ci.columns;
                if (cicols != null) for (EntitySchemaUtil.ColumnDef cc : cicols) names.add(cc.name);
                String colsKey = ciBase + "/columns";
                if (!registry.exists(colsKey)) {
                    registry.putList(colsKey, names, DataType.REG_MULTI_SZ);
                }
            }
        }
        return base;
    }

    public String getEntityRootKeyPath(Class<? extends DsTableAdapter> clazz, String rootKeyPath) {
        if (clazz == null || rootKeyPath == null) {
            throw new IllegalArgumentException("clazz/rootKeyPath 非空");
        }
        EntitySchemaUtil.SchemaDef schema = EntitySchemaUtil.schemaOf(clazz);
        if (schema == null || schema.schemaId == null) {
            throw new IllegalStateException("schemaOf 失败：" + clazz.getName());
        }
        return ("/".equals(rootKeyPath) ? "" : rootKeyPath) + "/" + schema.schemaId + "/" + clazz.getSimpleName();
    }

    public List<String> listEntitySchemas(String rootKeyPath) throws IOException {
        List<String> names = registry.listChildren(rootKeyPath);
        if (names == null) return Collections.emptyList();
        return new ArrayList<>(names);
    }

    public List<EntitySchemaUtil.SchemaDef> loadEntitySchemasFromRegistry(String rootKeyPath) throws IOException {
        List<String> schemaIds = listEntitySchemas(rootKeyPath);
        if (schemaIds.isEmpty()) return Collections.emptyList();
        List<EntitySchemaUtil.SchemaDef> out = new ArrayList<>();
        for (String sid : schemaIds) {
            List<EntitySchemaUtil.SchemaDef> subs = loadEntitySchemas(rootKeyPath, sid);
            if (subs != null) out.addAll(subs);
        }
        return out;
    }

    public List<EntitySchemaUtil.SchemaDef> loadEntitySchemas(String rootKeyPath, String schemaId) throws IOException {
        String schemaDir = ("/".equals(rootKeyPath) ? "" : rootKeyPath) + "/" + schemaId;
        List<String> simpleNames = registry.listChildren(schemaDir);
        if (simpleNames == null || simpleNames.isEmpty()) return Collections.emptyList();
        List<EntitySchemaUtil.SchemaDef> out = new ArrayList<>(simpleNames.size());
        for (String sn : simpleNames) {
            EntitySchemaUtil.SchemaDef def = loadSingleSchemaDef(schemaDir, sn, schemaId);
            if (def != null) out.add(def);
        }
        return out;
    }

    public Class<? extends DsTableAdapter> resolveEntityClass(String rootKeyPath, EntitySchemaUtil.SchemaDef schema) throws IOException, ClassNotFoundException {
        if (schema == null || schema.schemaId == null) throw new IllegalArgumentException("schema 非空");
        String schemaDir = ("/".equals(rootKeyPath) ? "" : rootKeyPath) + "/" + schema.schemaId;
        List<String> sns = registry.listChildren(schemaDir);
        if (sns == null) return null;
        for (String sn : sns) {
            RegistryEntry ce = registry.get(schemaDir + "/" + sn + "/.className");
            if (ce == null || !ce.isPresent()) continue;
            String className = ce.asString();
            if (className == null) continue;
            EntitySchemaUtil.SchemaDef sd = loadSingleSchemaDef(schemaDir, sn, schema.schemaId);
            if (sd == null) continue;
            if (schema.schemaId.equals(sd.schemaId)) {
                Class<?> c = Class.forName(className);
                if (DsTableAdapter.class.isAssignableFrom(c)) {
                    return c.asSubclass(DsTableAdapter.class);
                }
            }
        }
        return null;
    }

    private EntitySchemaUtil.SchemaDef loadSingleSchemaDef(String schemaDir, String simpleName, String schemaIdHint) throws IOException {
        String base = schemaDir + "/" + simpleName;
        RegistryEntry idE = registry.get(base + "/.schemaId");
        String sid = (idE != null && idE.isPresent()) ? idE.asString() : schemaIdHint;
        int rowLength = 0;
        RegistryEntry rl = registry.get(base + "/.rowLength");
        if (rl != null && rl.isPresent()) rowLength = (int) rl.asLong();
        String columnsPath = base + "/columns";
        List<EntitySchemaUtil.ColumnDef> columns = new ArrayList<>();
        Map<String, EntitySchemaUtil.ColumnDef> colByName = new LinkedHashMap<>();
        List<String> colNames = registry.listChildren(columnsPath);
        if (colNames != null) {
            for (String cn : colNames) {
                String colBase = columnsPath + "/" + cn;
                String typeName = readStr(colBase + "/type");
                int length = (int) readLong(colBase + "/length");
                boolean indexed = readLong(colBase + "/indexed") != 0L;
                boolean mvcc = readLong(colBase + "/mvcc") != 0L;
                int precision = (int) readLong(colBase + "/precision");
                int scale = (int) readLong(colBase + "/scale");
                String defaultValue = readStr(colBase + "/default");
                EntitySchemaUtil.ColumnDef cd = new EntitySchemaUtil.ColumnDef(
                        cn, typeName == null ? "" : typeName, length, precision, scale,
                        defaultValue, indexed, mvcc, (Field) null);
                columns.add(cd);
                colByName.put(cn, cd);
            }
        }
        String compositesPath = base + "/composites";
        List<EntitySchemaUtil.CompositeDef> composites = new ArrayList<>();
        List<String> cpNames = registry.listChildren(compositesPath);
        if (cpNames != null) {
            for (String cn : cpNames) {
                String cpBase = compositesPath + "/" + cn;
                String group = readStr(cpBase + "/group");
                int length = (int) readLong(cpBase + "/length");
                composites.add(new EntitySchemaUtil.CompositeDef(cn, group == null ? "" : group, length, 0, 0, (Field) null));
            }
        }
        String cindexPath = base + "/compositeIndexes";
        List<EntitySchemaUtil.CompositeIndexDef> cidxs = new ArrayList<>();
        List<String> ciNames = registry.listChildren(cindexPath);
        if (ciNames != null) {
            for (String ciName : ciNames) {
                String colsKey = cindexPath + "/" + ciName + "/columns";
                RegistryEntry ce = registry.get(colsKey);
                List<String> names;
                if (ce != null && ce.isPresent()) {
                    List<String> lst = ce.asList();
                    names = (lst != null) ? lst : Collections.emptyList();
                } else {
                    names = Collections.emptyList();
                }
                List<EntitySchemaUtil.ColumnDef> ciCols = new ArrayList<>(names.size());
                for (String n : names) {
                    EntitySchemaUtil.ColumnDef cc = colByName.get(n);
                    if (cc != null) ciCols.add(cc);
                }
                cidxs.add(new EntitySchemaUtil.CompositeIndexDef(ciName, ciCols));
            }
        }
        return new EntitySchemaUtil.SchemaDef(sid, rowLength, columns, composites, cidxs);
    }

    private String readStr(String key) throws IOException {
        RegistryEntry e = registry.get(key);
        if (e == null || !e.isPresent()) return null;
        return e.asString();
    }

    private long readLong(String key) throws IOException {
        RegistryEntry e = registry.get(key);
        if (e == null || !e.isPresent()) return 0L;
        return e.asLong();
    }
}
