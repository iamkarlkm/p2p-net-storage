package com.q3lives.ds.database.schema;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;
import com.q3lives.ds.registry.meta.DsRegistryInstance;
import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;
import com.q3lives.ds.util.DsPathUtil;
import com.q3lives.ds.enums.DataType;
import java.io.File;
import java.io.IOException;

/**
 * 行存 Index 定义定位器（类 idSet 指针）
 *
 * P10 T2 改造：新增 DsRegistry 登记 /orm/row/<entityClass>/idSet/ 3 个 leaf
 *   - legacySchemaId (REG_SZ) : "legacy"（走 ids.set） 或 schemaId（走 ids_<schemaId>.set）
 *   - idsFileAbsolutePath (REG_SZ)  : ids.set/ids_xxx.set 的绝对路径（幂等不覆盖）
 *   - createdAtEpochMs (REG_QWORD) : 首次登记时间 ms
 *
 * 不变式：公开方法 indexOf(File, Class) 签名零改。
 */
public final class EntityIndexUtil {
    private EntityIndexUtil() {
    }

    public static IndexDef indexOf(File dbRoot, Class<? extends DsTableAdapter> clazz) {
        String spacePath = DsPathUtil.dottedToLinuxPath(clazz.getName(), "entityClass");
        File dir = new File(dbRoot, "indexes/" + spacePath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        EntitySchemaUtil.SchemaDef schema = EntitySchemaUtil.schemaOf(clazz);
        File legacyIds = new File(dir, "ids.set");
        File schemaIds = new File(dir, "ids_" + schema.schemaId + ".set");

        // ---------- Registry 优先读（HYBRID/REGISTRY mode）----------
        if (MetaMode.isGlobalRegistryOrHybrid()) {
            IndexDef reg = tryFromRegistry(dbRoot, clazz, dir, schema, legacyIds, schemaIds);
            if (reg != null) return reg;
        }

        // ---------- YAML/legacy 侧原始逻辑 ----------
        IndexDef result;
        if (legacyIds.exists() && !schemaIds.exists()) {
            result = new IndexDef(legacyIds, "rows", schema.rowLength, true);
        } else {
            result = new IndexDef(schemaIds, "rows_" + schema.schemaId, schema.rowLength, false);
        }

        // ---------- Registry 登记（HYBRID/REGISTRY mode，幂等：存在则不覆盖）----------
        if (MetaMode.isGlobalRegistryOrHybrid()) {
            registerIdSet(dbRoot, clazz, schema, result);
        }
        return result;
    }

    private static IndexDef tryFromRegistry(File dbRoot,
                                            Class<? extends DsTableAdapter> clazz,
                                            File dir,
                                            EntitySchemaUtil.SchemaDef schema,
                                            File legacyIds,
                                            File schemaIds) {
        try {
            DsRegistry reg = DsRegistryInstance.get(dbRoot);
            String entityClass = clazz.getName();
            RegistryEntry idSetLegacy = reg.get(RegistryMetaPaths.rowIdSetLegacySchemaId(entityClass));
            if (idSetLegacy == null || !idSetLegacy.isPresent()) return null;

            String legacy = idSetLegacy.asString();
            if (legacy == null) return null;

            // 从 abs path 取
            RegistryEntry abs = reg.get(RegistryMetaPaths.rowIdSetAbsolutePath(entityClass));
            File file;
            if (abs != null && abs.isPresent()) {
                file = new File(abs.asString());
            } else if ("legacy".equals(legacy)) {
                file = legacyIds;
            } else if (legacy.equals(schema.schemaId)) {
                file = schemaIds;
            } else {
                file = new File(dir, "ids_" + legacy + ".set");
            }

            boolean isLegacy = "legacy".equals(legacy);
            String rowType = isLegacy ? "rows" : ("rows_" + (legacy.equals(schema.schemaId) ? schema.schemaId : legacy));
            // 为了兼容 "legacy" 与未知 schemaId，强制保证至少 idsFile 指向合理
            // 如果 Registry 记录的文件不存在，fallback 到 YAML 原始逻辑（返回 null 让外层跑原流程）
            if (!file.exists()) return null;

            return new IndexDef(file, rowType, schema.rowLength, isLegacy);
        } catch (Exception ex) {
            // 读 Registry 任何异常 → 降级 YAML，不抛
            return null;
        }
    }

    private static void registerIdSet(File dbRoot,
                                      Class<? extends DsTableAdapter> clazz,
                                      EntitySchemaUtil.SchemaDef schema,
                                      IndexDef def) {
        try {
            DsRegistry reg = DsRegistryInstance.get(dbRoot);
            String entityClass = clazz.getName();
            String legacySchemaIdPath = RegistryMetaPaths.rowIdSetLegacySchemaId(entityClass);
            // 幂等：已存在则跳过覆写
            RegistryEntry existing = reg.get(legacySchemaIdPath);
            if (existing != null && existing.isPresent()) return;

            reg.mkdirs(RegistryMetaPaths.rowIdSetRoot(entityClass));
            String legacySchemaIdVal = def.legacy ? "legacy" : schema.schemaId;
            reg.putString(legacySchemaIdPath, legacySchemaIdVal, DataType.REG_SZ);
            reg.putString(RegistryMetaPaths.rowIdSetAbsolutePath(entityClass),
                def.idsFile.getAbsolutePath(), DataType.REG_SZ);
            reg.putLong(RegistryMetaPaths.rowIdSetCreatedAtMs(entityClass),
                DataType.REG_QWORD, System.currentTimeMillis());
        } catch (IOException ex) {
            // 登记失败（磁盘满等），YAML 路径已经可用，不 throw，打印 WARN 避免影响正常业务
            System.err.println("[EntityIndexUtil] WARN 登记 ClassIdSet 到 Registry 失败: "
                + clazz.getName() + " -> " + ex.getMessage());
        }
    }

    public static final class IndexDef {
        public final File idsFile;
        public final String rowType;
        public final int rowLength;
        public final boolean legacy;

        public IndexDef(File idsFile, String rowType, int rowLength, boolean legacy) {
            this.idsFile = idsFile;
            this.rowType = rowType;
            this.rowLength = rowLength;
            this.legacy = legacy;
        }
    }
}
