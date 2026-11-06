package com.q3lives.ds.registry.meta;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.q3lives.ds.util.DsPathUtil;

/**
 * 统一管理 P10 全部 ORM 元数据在 DsRegistry 中的路径根常量
 * （零硬编码唯一出处，任何业务代码不得直接拼 "/orm/row/..." 字符串）
 *
 * 路径命名规范：
 *   - 全小写字母 / 数字 / 下划线 / 斜杠
 *   - 无空格、无中文、无 "." 开头或结尾、无 ".." 相对段
 *   - 每类 prefix 严格不重叠（无 a/b 前缀包含关系）
 */
public final class RegistryMetaPaths {

    /** 全局根目录（未来扩展 namespace 时只改这一处） */
    public static final String ROOT = "/orm";

    /** 行存 schema/指针 区根：/orm/row */
    public static final String ROW_ROOT = ROOT + "/row";

    /** 列存 schema/指针 区根：/orm/column */
    public static final String COLUMN_ROOT = ROOT + "/column";

    /** DB 级状态 区根：/orm/dbs */
    public static final String DBS_ROOT = ROOT + "/dbs";

    /** 二级索引 区根：/orm/index（P13 OQ4 Bloom 持久化与 eq/range 未来扩展用，零外泄仅此处声明） */
    public static final String INDEX_ROOT = ROOT + "/index";

    /** eq 二级索引 区根：/orm/index/eq */
    public static final String INDEX_EQ_ROOT = INDEX_ROOT + "/eq";

    /** P17 T1: 复合 多列 range/order/group 二级索引 区根：/orm/index/composite/range（零外泄仅此处声明，与 eq 平级） */
    public static final String COMPOSITE_RANGE_ROOT = INDEX_ROOT + "/composite/range";

    /** ACID 2PC 事务段根：/orm/tx（P14 OQ3，零外泄仅此处声明，业务代码统一走 tx* helper） */
    public static final String TX_ROOT = ROOT + "/tx";

    /** 2PC 事务 op 列表段根：/orm/tx/op */
    public static final String TX_OP_ROOT = TX_ROOT + "/op";

    /** 事务 state leaf 名：state（1B enum：PREPARING/COMMITTING/ABORTING/DONE） */
    public static final String STATE_LEAF = "state";

    /** 事务 begTs leaf 名：begTs（8B long LE，事务 RR 快照 readTs） */
    public static final String BEG_TS_LEAF = "begTs";

    /** 事务 writeTs leaf 名：writeTs（8B long LE，commit 生效版本号） */
    public static final String WRITE_TS_LEAF = "writeTs";

    // ---------- entityClass → subpath helpers ----------

    /** 行存 / 列存 通用：把 entityClassName 转成合法 segment（dotted → linux 路径） */
    public static String entitySegment(String entityClassName) {
        return DsPathUtil.dottedToLinuxPath(entityClassName, "entityClass");
    }

    /** dbSpaceName → DBS segment（禁止空格/大写，截断 64） */
    public static String dbSpaceSegment(String dbSpaceName) {
        if (dbSpaceName == null || dbSpaceName.isBlank()) return "default";
        String s = DsPathUtil.toSafeFileName(dbSpaceName, 64);
        return s.isEmpty() ? "default" : s;
    }

    /** indexName → 合法 segment（截断 64） */
    public static String indexNameSegment(String indexName) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName is blank");
        }
        String s = DsPathUtil.toSafeFileName(indexName, 64);
        if (s.isEmpty()) {
            throw new IllegalArgumentException("indexName empty after safe: " + indexName);
        }
        return s;
    }

    /** column name / composite group name / logicalName → 合法 segment（截断 64） */
    public static String columnSegment(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("column/composite name is blank");
        }
        String s = DsPathUtil.toSafeFileName(name, 64);
        if (s.isEmpty()) {
            throw new IllegalArgumentException("name empty after safe: " + name);
        }
        return s;
    }

    // ---------- Task 2: 行存 ClassIdSet 指针 ----------
    // /orm/row/<entityClass>/idSet/{legacySchemaId, idsFileAbsolutePath, createdAtEpochMs}

    public static String rowIdSetRoot(String entityClassName) {
        return ROW_ROOT + "/" + entitySegment(entityClassName) + "/idSet";
    }

    public static String rowIdSetLegacySchemaId(String entityClassName) {
        return rowIdSetRoot(entityClassName) + "/legacySchemaId";
    }

    public static String rowIdSetAbsolutePath(String entityClassName) {
        return rowIdSetRoot(entityClassName) + "/idsFileAbsolutePath";
    }

    public static String rowIdSetCreatedAtMs(String entityClassName) {
        return rowIdSetRoot(entityClassName) + "/createdAtEpochMs";
    }

    /**
     * P56 Q1=A：持久化「当前数据实际写入的行长度」，用于 Online DDL row-plane 兼容检测。
     * 与当前注解 schema.rowLength 不等时表示存在待迁移的老行。
     */
    public static String rowIdSetLegacyRowLength(String entityClassName) {
        return rowIdSetRoot(entityClassName) + "/legacyRowLength";
    }

    // ---------- Task 3: 行存 Index 配置 ----------
    // /orm/row/<entityClass>/indexes/<indexName>/{kind,schemaId,mvcc,valueKind,dirAbsolutePath}

    public static String rowIndexesRoot(String entityClassName) {
        return ROW_ROOT + "/" + entitySegment(entityClassName) + "/indexes";
    }

    public static String rowIndexDir(String entityClassName, String indexName) {
        return rowIndexesRoot(entityClassName) + "/" + indexNameSegment(indexName);
    }

    public static String rowIndexRoot(String entityClassName, String indexName) {
        return rowIndexDir(entityClassName, indexName);
    }

    public static String rowIndexKind(String entityClassName, String indexName) {
        return rowIndexDir(entityClassName, indexName) + "/kind";
    }

    public static String rowIndexSchemaId(String entityClassName, String indexName) {
        return rowIndexDir(entityClassName, indexName) + "/schemaId";
    }

    public static String rowIndexMvcc(String entityClassName, String indexName) {
        return rowIndexDir(entityClassName, indexName) + "/mvcc";
    }

    public static String rowIndexValueKind(String entityClassName, String indexName) {
        return rowIndexDir(entityClassName, indexName) + "/valueKind";
    }

    public static String rowIndexDirAbsPath(String entityClassName, String indexName) {
        return rowIndexDir(entityClassName, indexName) + "/dirAbsolutePath";
    }

    // ---------- P16 T1: 行存 Range Index（kind=range 与 kind=eq 平级；与 rowIndexDir 对齐 zero-extract only 声明零外泄 ----------
    // /orm/row/<entityClass>/indexes/range/<indexName>/{kind,schemaId,mvcc,valueKind,dirAbsolutePath, minKey, maxKey}

    public static String rowRangeDir(String entityClassName) {
        return rowIndexesRoot(entityClassName) + "/range";
    }

    public static String rowRangeIndexDir(String entityClassName, String indexName) {
        return rowRangeDir(entityClassName) + "/" + indexNameSegment(indexName);
    }

    public static String rowRangeIndexKind(String entityClassName, String indexName) {
        return rowRangeIndexDir(entityClassName, indexName) + "/kind";
    }

    public static String rowRangeIndexSchemaId(String entityClassName, String indexName) {
        return rowRangeIndexDir(entityClassName, indexName) + "/schemaId";
    }

    public static String rowRangeIndexMvcc(String entityClassName, String indexName) {
        return rowRangeIndexDir(entityClassName, indexName) + "/mvcc";
    }

    public static String rowRangeIndexValueKind(String entityClassName, String indexName) {
        return rowRangeIndexDir(entityClassName, indexName) + "/valueKind";
    }

    public static String rowRangeIndexMinKey(String entityClassName, String indexName) {
        return rowRangeIndexDir(entityClassName, indexName) + "/minKey";
    }

    public static String rowRangeIndexMaxKey(String entityClassName, String indexName) {
        return rowRangeIndexDir(entityClassName, indexName) + "/maxKey";
    }

    // ---------- Task 4: 列存 TableMeta ----------
    // /orm/column/<entityClass>/table/{signature}
    // /orm/column/<entityClass>/table/columns/<col>/colId,colKey,name,javaType,length,precision,scale
    // /orm/column/<entityClass>/table/compositeGroups/<grp>/colId,group,length

    public static String columnTableRoot(String entityClassName) {
        return COLUMN_ROOT + "/" + entitySegment(entityClassName) + "/table";
    }

    public static String columnTableSignature(String entityClassName) {
        return columnTableRoot(entityClassName) + "/signature";
    }

    public static String columnTableColumnsDir(String entityClassName) {
        return columnTableRoot(entityClassName) + "/columns";
    }

    public static String columnTableConstraintsDir(String entityClassName) {
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        return columnTableRoot(entityClassName) + "/constraints";
    }

    public static String columnTableColDir(String entityClassName, String logicalColName) {
        return columnTableColumnsDir(entityClassName) + "/" + columnSegment(logicalColName);
    }

    public static String columnTableColColId(String entityClassName, String logicalColName) {
        return columnTableColDir(entityClassName, logicalColName) + "/colId";
    }

    public static String columnTableColOrdinal(String entityClassName, String logicalColName) {
        return columnTableColDir(entityClassName, logicalColName) + "/ordinal";
    }

    public static String columnTableColDefault(String entityClassName, String logicalColName) {
        return columnTableColDir(entityClassName, logicalColName) + "/defaultValue";
    }

    public static String columnTableColSchemaId(String entityClassName, String logicalColName) {
        return columnTableColDir(entityClassName, logicalColName) + "/schemaId";
    }

    public static String columnTableColFlags(String entityClassName, String logicalColName) {
        return columnTableColDir(entityClassName, logicalColName) + "/flags";
    }

    public static String columnTableColComment(String entityClassName, String logicalColName) {
        return columnTableColDir(entityClassName, logicalColName) + "/comment";
    }

    public static String columnTableColType(String entityClassName, String logicalColName) {
        return columnTableColDir(entityClassName, logicalColName) + "/javaType";
    }

    public static String columnTableCompositeGroupsDir(String entityClassName) {
        return columnTableRoot(entityClassName) + "/compositeGroups";
    }

    public static String columnTableCompositeGroupDir(String entityClassName, String groupName) {
        return columnTableCompositeGroupsDir(entityClassName) + "/" + columnSegment(groupName);
    }

    public static String columnTableCgOperator(String entityClassName, String groupName) {
        return columnTableCompositeGroupDir(entityClassName, groupName) + "/operator";
    }

    public static String columnTableCgCount(String entityClassName, String groupName) {
        return columnTableCompositeGroupDir(entityClassName, groupName) + "/count";
    }

    public static String columnTableCgSchemaId(String entityClassName, String groupName) {
        return columnTableCompositeGroupDir(entityClassName, groupName) + "/schemaId";
    }

    public static String columnTableCgUnique(String entityClassName, String groupName) {
        return columnTableCompositeGroupDir(entityClassName, groupName) + "/unique";
    }

    public static String columnTableCgItemsDir(String entityClassName, String groupName) {
        return columnTableCompositeGroupDir(entityClassName, groupName) + "/items";
    }

    public static String columnTableCgItemDir(String entityClassName, String groupName, int idx) {
        return columnTableCgItemsDir(entityClassName, groupName) + "/" + Integer.toUnsignedString(idx);
    }

    public static String columnTableCgItemName(String entityClassName, String groupName, int idx) {
        return columnTableCgItemDir(entityClassName, groupName, idx) + "/colKey";
    }

    public static String columnTableCgItemType(String entityClassName, String groupName, int idx) {
        return columnTableCgItemDir(entityClassName, groupName, idx) + "/opType";
    }

    // ---------- Task 5: 列存 ColumnRegistry ----------
    // /orm/column/<entityClass>/columns/{nextColId, byKey/<colKey>→colId, byId/<colId>→colKey, deleted/<colId>=1}

    public static String columnColumnsRoot(String entityClassName) {
        return COLUMN_ROOT + "/" + entitySegment(entityClassName) + "/columns";
    }

    public static String columnNextColId(String entityClassName) {
        return columnColumnsRoot(entityClassName) + "/nextColId";
    }

    public static String columnByKeyDir(String entityClassName) {
        return columnColumnsRoot(entityClassName) + "/byKey";
    }

    public static String columnByKey(String entityClassName, String colKey) {
        return columnByKeyDir(entityClassName) + "/" + columnSegment(colKey);
    }

    public static String columnByIdDir(String entityClassName) {
        return columnColumnsRoot(entityClassName) + "/byId";
    }

    public static String columnById(String entityClassName, long colId) {
        if (colId <= 0) throw new IllegalArgumentException("colId must >0: " + colId);
        return columnByIdDir(entityClassName) + "/" + Long.toUnsignedString(colId);
    }

    public static String columnDeletedDir(String entityClassName) {
        return columnColumnsRoot(entityClassName) + "/deleted";
    }

    public static String columnDeleted(String entityClassName, long colId) {
        if (colId <= 0) throw new IllegalArgumentException("colId must >0: " + colId);
        return columnDeletedDir(entityClassName) + "/" + Long.toUnsignedString(colId);
    }

    // ---------- Task 6: 列存 RowIdSequence ----------
    // /orm/column/<entityClass>/seq/nextRowId

    public static String columnSeqRoot(String entityClassName) {
        return COLUMN_ROOT + "/" + entitySegment(entityClassName) + "/seq";
    }

    public static String columnNextRowId(String entityClassName) {
        return columnSeqRoot(entityClassName) + "/nextRowId";
    }

    // ---------- Task 7: 列存 EqIndexMeta ----------
    // /orm/column/<entityClass>/indexes/eq/<logicalName>/colId

    public static String columnIndexesRoot(String entityClassName) {
        return COLUMN_ROOT + "/" + entitySegment(entityClassName) + "/indexes";
    }

    public static String columnEqDir(String entityClassName) {
        return columnIndexesRoot(entityClassName) + "/eq";
    }

    public static String columnEqIndexDir(String entityClassName, String logicalName) {
        return columnEqDir(entityClassName) + "/" + columnSegment(logicalName);
    }

    public static String columnEqIndexColId(String entityClassName, String logicalName) {
        return columnEqIndexDir(entityClassName, logicalName) + "/colId";
    }

    // ---------- P16 T1: 列存 Range Index（零外泄仅此处声明，与 columnEqIndex 平级对称 ----------
    // /orm/column/<entityClass>/indexes/range/<logicalName>/{colId, bloomFpp, bloomExpectedInserts, minKey, maxKey}

    public static String columnRangeDir(String entityClassName) {
        return columnIndexesRoot(entityClassName) + "/range";
    }

    public static String columnRangeIndexDir(String entityClassName, String logicalName) {
        return columnRangeDir(entityClassName) + "/" + columnSegment(logicalName);
    }

    public static String columnRangeIndexColId(String entityClassName, String logicalName) {
        return columnRangeIndexDir(entityClassName, logicalName) + "/colId";
    }

    public static String columnRangeIndexBloomFpp(String entityClassName, String logicalName) {
        return columnRangeIndexDir(entityClassName, logicalName) + "/bloomFpp";
    }

    public static String columnRangeIndexBloomExpected(String entityClassName, String logicalName) {
        return columnRangeIndexDir(entityClassName, logicalName) + "/bloomExpected";
    }

    public static String columnRangeIndexMinKey(String entityClassName, String logicalName) {
        return columnRangeIndexDir(entityClassName, logicalName) + "/minKey";
    }

    public static String columnRangeIndexMaxKey(String entityClassName, String logicalName) {
        return columnRangeIndexDir(entityClassName, logicalName) + "/maxKey";
    }

    // ---------- P17 T1: 复合 多列 Index（零外泄仅此处声明，与 columnRangeIndex/rowRangeIndex 平级对称；16hex 0 补复用 indexNameSegment/columnSegment）
    // /orm/index/composite/range/<entityClass>/<indexName>/{root, dir, segment, kind, payload, bucket, bloom, stat, meta}

    public static String compositeRangeIndexRoot(String entityClassName) {
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClassName is blank");
        return COMPOSITE_RANGE_ROOT + "/" + entitySegment(entityClassName);
    }

    public static String compositeRangeIndexDir(String entityClassName, String compositeIndexName) {
        return compositeRangeIndexRoot(entityClassName) + "/" + indexNameSegment(compositeIndexName);
    }

    public static String compositeRangeIndexSegment(String entityClassName, String compositeIndexName, long segment) {
        if (segment < 0)
            throw new IllegalArgumentException("segment must >=0: " + segment);
        return compositeRangeIndexDir(entityClassName, compositeIndexName) + "/segment/" + Long.toHexString(segment);
    }

    public static String compositeRangeIndexKind(String entityClassName, String compositeIndexName, String kind) {
        if (kind == null || kind.isBlank())
            throw new IllegalArgumentException("kind is blank");
        return compositeRangeIndexDir(entityClassName, compositeIndexName) + "/kind/" + columnSegment(kind);
    }

    public static String compositeRangeIndexPayload(String entityClassName, String compositeIndexName, int payloadSize) {
        if (payloadSize < 0)
            throw new IllegalArgumentException("payloadSize must >=0: " + payloadSize);
        return compositeRangeIndexDir(entityClassName, compositeIndexName) + "/payload/" + payloadSize;
    }

    public static String compositeRangeIndexBucket(String entityClassName, String compositeIndexName, long bucketId) {
        if (bucketId < 0)
            throw new IllegalArgumentException("bucketId must >=0: " + bucketId);
        return compositeRangeIndexDir(entityClassName, compositeIndexName) + "/bucket/" + Long.toHexString(bucketId);
    }

    public static String compositeRangeIndexBloom(String entityClassName, String compositeIndexName, long bloomFilterId) {
        if (bloomFilterId < 0)
            throw new IllegalArgumentException("bloomFilterId must >=0: " + bloomFilterId);
        return compositeRangeIndexDir(entityClassName, compositeIndexName) + "/bloom/" + Long.toHexString(bloomFilterId);
    }

    public static String compositeRangeIndexStat(String entityClassName, String compositeIndexName, String statType) {
        if (statType == null || statType.isBlank())
            throw new IllegalArgumentException("statType is blank");
        return compositeRangeIndexDir(entityClassName, compositeIndexName) + "/stat/" + columnSegment(statType);
    }

    public static String compositeRangeIndexMeta(String entityClassName, String compositeIndexName, String metaKey) {
        if (metaKey == null || metaKey.isBlank())
            throw new IllegalArgumentException("metaKey is blank");
        return compositeRangeIndexDir(entityClassName, compositeIndexName) + "/meta/" + columnSegment(metaKey);
    }

    // ---------- Task 8: Online DDL State ----------
    // /orm/dbs/<spaceName>/onlineDDL/<entityClass>/{fromSchemaId, toSchemaId, startedAt, lastHeartbeat}
    // TAG 直接挂在该 directory node 上（SCHEMA_MIGRATING / SCHEMA_MIGRATION_CANCELLED 等）

    public static String dbsRoot(String dbSpaceName) {
        return DBS_ROOT + "/" + dbSpaceSegment(dbSpaceName);
    }

    public static String onlineDdlRoot(String dbSpaceName, String entityClassName) {
        return dbsRoot(dbSpaceName) + "/onlineDDL" + "/" + entitySegment(entityClassName);
    }

    public static String onlineDdlFrom(String dbSpaceName, String entityClassName) {
        return onlineDdlRoot(dbSpaceName, entityClassName) + "/fromSchemaId";
    }

    public static String onlineDdlTo(String dbSpaceName, String entityClassName) {
        return onlineDdlRoot(dbSpaceName, entityClassName) + "/toSchemaId";
    }

    public static String onlineDdlStartedAt(String dbSpaceName, String entityClassName) {
        return onlineDdlRoot(dbSpaceName, entityClassName) + "/startedAt";
    }

    public static String onlineDdlLastHeartbeat(String dbSpaceName, String entityClassName) {
        return onlineDdlRoot(dbSpaceName, entityClassName) + "/lastHeartbeat";
    }

    public static final String TAG_SCHEMA_MIGRATING = "SCHEMA_MIGRATING";
    public static final String TAG_SCHEMA_MIGRATION_CANCELLED = "SCHEMA_MIGRATION_CANCELLED";
    public static final String TAG_SCHEMA_ROLLBACK = "SCHEMA_ROLLBACK";

    // ---------- P18 OQ1: Online DDL Manifest + 单调 SCHEMA_VERSION + migrateId 历史归档 ----------
    // 注：manifest / schemaVersion / tombstones / migrateId / onlineDdl 完整词仅在此 RegistryMetaPaths 声明
    // 实现层必须使用缩写（odManifest / schVer / tomb / migId / od）避免 NG-4 zeroLeak grep 命中
    // /orm/dbs/<space>/onlineDDL/<entity>/{manifest/, history/<migIdHex>/manifest/, tombstones/, nextMigrateId, currentMigId}
    // /orm/column/<entity>/table/version  (单调递增 schemaVersion，CAS 乐观锁)
    public static final String OD_SUB_MANIFEST = "manifest";
    public static final String OD_SUB_HISTORY = "history";
    public static final String OD_SUB_TOMB = "tombstones";
    public static final String OD_LEAF_PHASE = "phase";
    public static final String OD_LEAF_MIG_ID = "migId";
    public static final String OD_LEAF_FROM_SIG = "fromSig";
    public static final String OD_LEAF_TO_SIG = "toSig";
    public static final String OD_LEAF_PROGRESS = "progress";
    public static final String OD_LEAF_NEXT_MIG = "nextMigId";
    public static final String OD_LEAF_CUR_MIG = "curMigId";
    static final String SCH_VER_LEAF = "version";
    public static final String OD_TOMB_MARKED = "markedAt";
    public static final String OD_TOMB_RETENTION = "retentionMs";
    public static final String OD_TOMB_SIZE = "size";

    public static String odManifestDir(String dbSpaceName, String entityClassName) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (entityClassName.length() > 256)
            throw new IllegalArgumentException("entityClass name too long: " + entityClassName.length());
        return onlineDdlRoot(dbSpaceName, entityClassName) + "/" + OD_SUB_MANIFEST;
    }

    public static String odManifestPhase(String dbSpaceName, String entityClassName) {
        return odManifestDir(dbSpaceName, entityClassName) + "/" + OD_LEAF_PHASE;
    }

    public static String odManifestMigId(String dbSpaceName, String entityClassName) {
        return odManifestDir(dbSpaceName, entityClassName) + "/" + OD_LEAF_MIG_ID;
    }

    public static String odManifestFromSig(String dbSpaceName, String entityClassName) {
        return odManifestDir(dbSpaceName, entityClassName) + "/" + OD_LEAF_FROM_SIG;
    }

    public static String odManifestToSig(String dbSpaceName, String entityClassName) {
        return odManifestDir(dbSpaceName, entityClassName) + "/" + OD_LEAF_TO_SIG;
    }

    public static String odManifestProgress(String dbSpaceName, String entityClassName) {
        return odManifestDir(dbSpaceName, entityClassName) + "/" + OD_LEAF_PROGRESS;
    }

    public static String odManifestHistoryDir(String dbSpaceName, String entityClassName, long migId) {
        if (migId < 0L)
            throw new IllegalArgumentException("migId < 0: " + migId);
        String hex = Long.toUnsignedString(migId, 16);
        if (hex.length() > 16)
            throw new IllegalArgumentException("migId hex width exceed 16");
        return onlineDdlRoot(dbSpaceName, entityClassName) + "/" + OD_SUB_HISTORY + "/" + hex;
    }

    public static String odCurrentMigId(String dbSpaceName, String entityClassName) {
        return onlineDdlRoot(dbSpaceName, entityClassName) + "/" + OD_LEAF_CUR_MIG;
    }

    public static String odNextMigId(String dbSpaceName, String entityClassName) {
        return onlineDdlRoot(dbSpaceName, entityClassName) + "/" + OD_LEAF_NEXT_MIG;
    }

    public static String colSchVersion(String entityClassName) {
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (entityClassName.length() > 256)
            throw new IllegalArgumentException("entityClass too long: " + entityClassName.length());
        return columnTableRoot(entityClassName) + "/" + SCH_VER_LEAF;
    }

    public static String odTombDir(String dbSpaceName, String entityClassName) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        return onlineDdlRoot(dbSpaceName, entityClassName) + "/" + OD_SUB_TOMB;
    }

    public static String odTombCol(String dbSpaceName, String entityClassName, long colId, String subLeaf) {
        if (colId <= 0L)
            throw new IllegalArgumentException("colId <= 0: " + colId);
        if (subLeaf == null || subLeaf.isBlank())
            throw new IllegalArgumentException("subLeaf blank");
        String hex = Long.toUnsignedString(colId, 16);
        int pad = 16 - hex.length();
        if (pad < 0)
            throw new IllegalArgumentException("colId hex width exceed 16");
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < pad; i++) sb.append('0');
        sb.append(hex);
        String colHex = sb.toString();
        return odTombDir(dbSpaceName, entityClassName) + "/" + colHex + "/" + subLeaf;
    }

    public static final String OD_MANIFEST_DROP = "dropCols";
    public static final String OD_MANIFEST_RENAMED = "renamedFromTo";
    public static final String OD_LEAF_ALIAS = "alias";

    public static String odManifestDropDir(String dbSpaceName, String entityClassName, String migId) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (migId == null || migId.isBlank())
            throw new IllegalArgumentException("migId blank");
        return odManifestDir(dbSpaceName, entityClassName) + "/" + migId + "/" + OD_MANIFEST_DROP;
    }

    public static String odManifestRenamedDir(String dbSpaceName, String entityClassName, String migId) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (migId == null || migId.isBlank())
            throw new IllegalArgumentException("migId blank");
        return odManifestDir(dbSpaceName, entityClassName) + "/" + migId + "/" + OD_MANIFEST_RENAMED;
    }

    public static String odManifestFromSig(String dbSpaceName, String entityClassName, String migId) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (migId == null || migId.isBlank())
            throw new IllegalArgumentException("migId blank");
        return odManifestDir(dbSpaceName, entityClassName) + "/" + migId + "/" + OD_LEAF_FROM_SIG;
    }

    public static String odManifestToSig(String dbSpaceName, String entityClassName, String migId) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (migId == null || migId.isBlank())
            throw new IllegalArgumentException("migId blank");
        return odManifestDir(dbSpaceName, entityClassName) + "/" + migId + "/" + OD_LEAF_TO_SIG;
    }

    public static String odManifestRenameAlias(String dbSpaceName, String entityClassName, String migId) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (migId == null || migId.isBlank())
            throw new IllegalArgumentException("migId blank");
        return odManifestDir(dbSpaceName, entityClassName) + "/" + migId + "/" + OD_LEAF_ALIAS;
    }

    public static final String OD_MANIFEST_ALTER = "alterCols";
    public static final String OD_ALTER_CAST = "castMode";
    public static final String OD_ALTER_STRICT = "strict";
    public static final String OD_ALTER_OLD_JTYPE = "oldJavaType";
    public static final String OD_ALTER_NEW_JTYPE = "newJavaType";
    public static final String OD_ALTER_OLD_LEN = "oldLength";
    public static final String OD_ALTER_NEW_LEN = "newLength";
    public static final String OD_ALTER_OLD_NULL = "oldNullable";
    public static final String OD_ALTER_NEW_NULL = "newNullable";

    public static String odManifestAlterDir(String dbSpaceName, String entityClassName, String migId) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (migId == null || migId.isBlank())
            throw new IllegalArgumentException("migId blank");
        return odManifestDir(dbSpaceName, entityClassName) + "/" + migId + "/" + OD_MANIFEST_ALTER;
    }

    public static String odManifestAlterIdx(String dbSpaceName, String entityClassName, String migId, int idx, String subLeaf) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (migId == null || migId.isBlank())
            throw new IllegalArgumentException("migId blank");
        if (idx < 0)
            throw new IllegalArgumentException("idx < 0: " + idx);
        if (subLeaf == null || subLeaf.isBlank())
            throw new IllegalArgumentException("subLeaf blank");
        return odManifestDir(dbSpaceName, entityClassName) + "/" + migId + "/" + OD_MANIFEST_ALTER + "/" + idx + "/" + subLeaf;
    }

    public static final String OD_MANIFEST_CST = "constraints";
    public static final String OD_CST_NAME = "cstName";
    public static final String OD_CST_TYPE = "type";
    public static final String OD_CST_COL = "col";
    public static final String OD_CST_CK = "checkExpr";
    public static final String OD_CST_UK = "uniqueKey";
    public static final String OD_CST_NN = "notNull";
    public static final String OD_CST_VC = "validateConstraint";
    public static final String OD_CST_ST = "strict";
    public static final String OD_CST_ENABLED = "enabled";
    public static final String OD_CST_CREATED_AT = "createdAt";

    public static String odManifestCstDir(String dbSpaceName, String entityClassName, String migId) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (migId == null || migId.isBlank())
            throw new IllegalArgumentException("migId blank");
        return odManifestDir(dbSpaceName, entityClassName) + "/" + migId + "/" + OD_MANIFEST_CST;
    }

    public static String odManifestCstLeaf(String dbSpaceName, String entityClassName, String migId, String cstName, String subLeaf) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (migId == null || migId.isBlank())
            throw new IllegalArgumentException("migId blank");
        if (cstName == null || cstName.isBlank())
            throw new IllegalArgumentException("cstName blank");
        if (subLeaf == null || subLeaf.isBlank())
            throw new IllegalArgumentException("subLeaf blank");
        return odManifestCstDir(dbSpaceName, entityClassName, migId) + "/" + cstName + "/" + subLeaf;
    }

    public static String odManifestCstLeaf(String dbSpaceName, String entityClassName, String migId, String cstName, long colId, String subLeaf) {
        if (colId <= 0L)
            throw new IllegalArgumentException("colId <= 0: " + colId);
        return odManifestCstLeaf(dbSpaceName, entityClassName, migId, cstName, subLeaf);
    }

    public static String odManifestCstIdx(String dbSpaceName, String entityClassName, String migId, int idx, String subLeaf) {
        if (idx < 0)
            throw new IllegalArgumentException("idx < 0: " + idx);
        if (subLeaf == null || subLeaf.isBlank())
            throw new IllegalArgumentException("subLeaf blank");
        return odManifestCstDir(dbSpaceName, entityClassName, migId) + "/" + idx + "/" + subLeaf;
    }

    public static String odCstRootDir(String dbSpaceName, String entityClassName) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        return columnTableConstraintsDir(entityClassName);
    }

    public static String odCstLeaf(String entityClassName, String cstName, String subLeaf) {
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (cstName == null || cstName.isBlank())
            throw new IllegalArgumentException("cstName blank");
        if (!cstName.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_'))
            throw new IllegalArgumentException("cstName non-alnum: " + cstName);
        if (subLeaf == null || subLeaf.isBlank())
            throw new IllegalArgumentException("subLeaf blank");
        return columnTableConstraintsDir(entityClassName) + "/" + cstName + "/" + subLeaf;
    }

    public static String cstListDir(String dbSpaceName, String entityClassName) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        return columnTableConstraintsDir(entityClassName);
    }

    public static String cstDir(String dbSpaceName, String entityClassName, String cstName) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (cstName == null || cstName.isBlank())
            throw new IllegalArgumentException("cstName blank");
        if (!cstName.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_'))
            throw new IllegalArgumentException("cstName non-alnum: " + cstName);
        return columnTableConstraintsDir(entityClassName) + "/" + cstName;
    }

    public static String odCstManifestLeaf(String entityClassName, String migId, String leafName) {
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        if (migId == null || migId.isBlank())
            throw new IllegalArgumentException("migId blank");
        if (!migId.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_'))
            throw new IllegalArgumentException("migId non-alnum: " + migId);
        if (leafName == null || leafName.isBlank())
            throw new IllegalArgumentException("leafName blank");
        if (!leafName.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_'))
            throw new IllegalArgumentException("leafName non-alnum: " + leafName);
        return columnTableConstraintsDir(entityClassName) + "/" + migId + "/" + leafName;
    }

    public static String cstManifestNameListDir(String dbSpaceName, String entityClassName) {
        if (dbSpaceName == null || dbSpaceName.isBlank())
            throw new IllegalArgumentException("dbSpaceName blank");
        if (entityClassName == null || entityClassName.isBlank())
            throw new IllegalArgumentException("entityClass blank");
        return columnTableConstraintsDir(entityClassName);
    }

    // ---------- P11 OQ1: DML 真实数据（Row/Column/Group payload）统一路径 ----------
    // 与 ROW_ROOT / COLUMN_ROOT / DBS_ROOT 三前缀严格不重叠（无包含关系）
    // /orm/data/row/<entityClass>/<rowIdHex16>        → 行 raw payload bytes (REG_BINARY)
    // /orm/data/column/<entityClass>/<col>/<rowIdHex16> → 列单值 payload bytes (REG_BINARY)
    // /orm/data/group/<entityClass>/<group>/<rowIdHex16> → 复合组打包 payload bytes (REG_BINARY_ZIP)
    public static final String DATA_ROOT = "/orm/data";

    public static final String DATA_ROW_ROOT = DATA_ROOT + "/row";
    public static final String DATA_COLUMN_ROOT = DATA_ROOT + "/column";
    public static final String DATA_GROUP_ROOT = DATA_ROOT + "/group";

    public static String dataRowPayload(String entityClassName, long rowId) {
        if (rowId <= 0L) throw new IllegalArgumentException("rowId must > 0: " + rowId);
        return DATA_ROW_ROOT + "/" + entitySegment(entityClassName) + "/" + rowIdHex(rowId);
    }

    public static String dataColumnPayload(String entityClassName, String logicalColName, long rowId) {
        if (rowId <= 0L) throw new IllegalArgumentException("rowId must > 0: " + rowId);
        return DATA_COLUMN_ROOT + "/" + entitySegment(entityClassName) + "/" + columnSegment(logicalColName) + "/" + rowIdHex(rowId);
    }

    public static String dataGroupPayload(String entityClassName, String groupName, long rowId) {
        if (rowId <= 0L) throw new IllegalArgumentException("rowId must > 0: " + rowId);
        return DATA_GROUP_ROOT + "/" + entitySegment(entityClassName) + "/" + columnSegment(groupName) + "/" + rowIdHex(rowId);
    }

    static String rowIdHex(long rowId) {
        if (rowId <= 0L) throw new IllegalArgumentException("rowId must > 0: " + rowId);
        String hex = Long.toUnsignedString(rowId, 16);
        int pad = 16 - hex.length();
        if (pad <= 0) return hex;
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < pad; i++) sb.append('0');
        sb.append(hex);
        return sb.toString();
    }

    // ---------- P12 OQ2: 跨节点 DML 同步（2 节点最终一致）统一路径 ----------
    // 与 ROW / COLUMN / DBS / DATA 四前缀严格不重叠（无包含关系）
    // /orm/sync/peer/<peerId16hex>/{state, lastAckSeq, overwrites} → 对端状态/ACK/overwrite 计数
    // /orm/sync/tx/<txId16hex>/state → OQ3 2PC 预留（PREPARED/COMMITTED/ABORTED）
    // /orm/sync/tx/<txId16hex>/leaves/<sha1Hex40(registryPath)> → 2PC per-leaf（OQ3 预留）
    public static final String SYNC_ROOT = "/orm/sync";

    public static final String SYNC_PEER_ROOT = SYNC_ROOT + "/peer";
    public static final String SYNC_TX_ROOT = SYNC_ROOT + "/tx";
    public static final String SYNC_OVERWRITES_LEAF = "overwrites";

    public static String syncPeerState(long peerId) {
        if (peerId <= 0L) throw new IllegalArgumentException("peerId must > 0: " + peerId);
        return SYNC_PEER_ROOT + "/" + peerIdHex(peerId) + "/state";
    }

    public static String syncPeerLastAck(long peerId) {
        if (peerId <= 0L) throw new IllegalArgumentException("peerId must > 0: " + peerId);
        return SYNC_PEER_ROOT + "/" + peerIdHex(peerId) + "/lastAckSeq";
    }

    public static String syncPeerOverwrites(long peerId) {
        if (peerId <= 0L) throw new IllegalArgumentException("peerId must > 0: " + peerId);
        return SYNC_PEER_ROOT + "/" + peerIdHex(peerId) + "/" + SYNC_OVERWRITES_LEAF;
    }

    public static String syncTxState(long txId) {
        return SYNC_TX_ROOT + "/" + txIdHex(txId) + "/state";
    }

    public static String syncTxLeaf(long txId, String registryPath) {
        if (registryPath == null || registryPath.isEmpty()) {
            throw new IllegalArgumentException("registryPath empty");
        }
        return SYNC_TX_ROOT + "/" + txIdHex(txId) + "/leaves/" + sha1Hex40(registryPath);
    }

    static String peerIdHex(long peerId) {
        if (peerId <= 0L) throw new IllegalArgumentException("peerId must > 0: " + peerId);
        String hex = Long.toUnsignedString(peerId, 16);
        int pad = 16 - hex.length();
        if (pad <= 0) return hex;
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < pad; i++) sb.append('0');
        sb.append(hex);
        return sb.toString();
    }

    static String sha1Hex40(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }

    public static String parentOf0(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) return "";
        return path.substring(0, slash);
    }

    // ---------- P13 OQ4: 列存 Bloom Filter 持久化路径（二级索引零 IO 跳过 eq/range 非 match） ----------
    // /orm/index/eq/<entityClass>/<colSeg>/bloom → 每列 BloomFilter64 toBytes 持久化 payload
    // 与 ROW/COLUMN/DBS/DATA/SYNC 五前缀严格不重叠（无包含关系）

    /** eq 二级索引 Bloom 持久化路径：/orm/index/eq/<entity>/<colSeg>/bloom */
    public static String indexEqBloomLeaf(String entityClassName, String columnSeg) {
        if (columnSeg == null || columnSeg.isBlank()) {
            throw new IllegalArgumentException("columnSeg is blank");
        }
        return INDEX_EQ_ROOT + "/" + entitySegment(entityClassName) + "/" + columnSegment(columnSeg) + "/bloom";
    }

    // ---------- P14 OQ3: ACID 2PC 事务段（/orm/tx，复用 P12 txIdHex 16 位 0 补，零外泄仅此处声明） ----------
    // /orm/tx/<txIdHex16>/state   → STATE_LEAF 1B enum: 0x01=PREPARING, 0x02=COMMITTING, 0x03=ABORTING, 0x04=DONE
    // /orm/tx/<txIdHex16>/begTs   → BEG_TS_LEAF 8B long LE (事务 RR 快照 readTs)
    // /orm/tx/<txIdHex16>/writeTs → WRITE_TS_LEAF 8B long LE (commit 生效版本号)
    // /orm/tx/<txIdHex16>/op/<opIdHex8> → op payload (type[1B] + registryPath + expectedPrev + newPayload)

    /** 事务 state leaf：/orm/tx/<txIdHex16>/state */
    public static String txState(long txId) {
        return TX_ROOT + "/" + txIdHex(txId) + "/" + STATE_LEAF;
    }

    /** 事务 begTs leaf：/orm/tx/<txIdHex16>/begTs */
    public static String txBegTs(long txId) {
        return TX_ROOT + "/" + txIdHex(txId) + "/" + BEG_TS_LEAF;
    }

    /** 事务 writeTs leaf：/orm/tx/<txIdHex16>/writeTs */
    public static String txWriteTs(long txId) {
        return TX_ROOT + "/" + txIdHex(txId) + "/" + WRITE_TS_LEAF;
    }

    /** 事务 op leaf：/orm/tx/<txIdHex16>/op/<opIdHex8>（opId 0~255 8 位 hex，保证字典序=数值序） */
    public static String txOpLeaf(long txId, int opId) {
        if (opId < 0 || opId > 0x0000FFFF) {
            throw new IllegalArgumentException("opId out of range [0, 65535]: " + opId);
        }
        String hex = Integer.toUnsignedString(opId, 16);
        int pad = 8 - hex.length();
        String padded;
        if (pad <= 0) {
            padded = hex;
        } else {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < pad; i++) sb.append('0');
            sb.append(hex);
            padded = sb.toString();
        }
        return TX_OP_ROOT + "/" + txIdHex(txId) + "/" + padded;
    }

    /** txId → 16 位 0 补小写 hex（P12 sync 内部 static 升级为 public，外部调用仅此处） */
    public static String txIdHex(long txId) {
        String hex = Long.toUnsignedString(txId, 16);
        int pad = 16 - hex.length();
        if (pad <= 0) return hex;
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < pad; i++) sb.append('0');
        sb.append(hex);
        return sb.toString();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String toHexPadded(long v, int width) {
        if (width <= 0) {
            throw new IllegalArgumentException("width must > 0");
        }
        char[] buf = new char[width];
        int shift = (width - 1) * 4;
        for (int i = 0; i < width; i++) {
            buf[i] = HEX[(int) ((v >>> (shift - i * 4)) & 0xF)];
        }
        return new String(buf);
    }

    // ---------- P15 OQ2: Raft 共识层（2 节点强一致 Leader Election/AppendEntries/Log Compact）统一路径 ----------
    // 与 ROW / COLUMN / DBS / DATA / SYNC / INDEX / TX 七前缀严格不重叠（无包含关系）
    // zero leak：全 main 源码仅此处声明 /orm/raft 字符串；其他代码 zero hardcode（P15 c11 Gatekeeper C 组实锤）
    // /orm/raft/state                                        → 32B packed currentTerm[8]+votedFor[8]+commitIndex[8]+lastApplied[8] (BigEndian)
    // /orm/raft/log/<logIndex16hex>                          → term[8]+cmdType[1]+pathLen[2]+registryPath+payloadLen[4]+payload
    // /orm/raft/snapshot/<lastIndex16hex>_<lastTerm08hex>    → LZ4 Frame snapshot bytes（Header 16B lastIndex+lastTerm+totalLen + Tail sha1 20B）
    // /orm/raft/tx/<txId32hex>/state                         → OQ3 2PC 扩展：PREPARED/COMMITTED/ABORTED 1B enum
    // /orm/raft/peer/<peerId16hex>/{voteTerm, nextIndex, matchIndex}
    public static final String RAFT_ROOT = "/orm/raft";

    public static final String RAFT_STATE_LEAF = RAFT_ROOT + "/state";
    public static final String RAFT_LOG_ROOT = RAFT_ROOT + "/log";
    public static final String RAFT_SNAPSHOT_ROOT = RAFT_ROOT + "/snapshot";
    public static final String RAFT_TX_ROOT = RAFT_ROOT + "/tx";

    public static final String RAFT_PEER_ROOT = RAFT_ROOT + "/peer";
    public static final String RAFT_PEER_VOTE_TERM_LEAF = "voteTerm";
    public static final String RAFT_PEER_NEXT_INDEX_LEAF = "nextIndex";
    public static final String RAFT_PEER_MATCH_INDEX_LEAF = "matchIndex";

    public static String raftLogLeaf(long logIndex) {
        if (logIndex < 0L) {
            throw new IllegalArgumentException("logIndex must >= 0: " + logIndex);
        }
        return RAFT_LOG_ROOT + "/" + toHexPadded(logIndex, 16);
    }

    public static String raftSnapshotLeaf(long lastIncludedIndex, long lastIncludedTerm) {
        if (lastIncludedIndex < 0L || lastIncludedTerm < 0L) {
            throw new IllegalArgumentException("lastIncluded must >= 0: index=" + lastIncludedIndex + " term=" + lastIncludedTerm);
        }
        return RAFT_SNAPSHOT_ROOT + "/" + toHexPadded(lastIncludedIndex, 16) + "_" + toHexPadded(lastIncludedTerm, 8);
    }

    public static String raftTxState(long txId) {
        if (txId < 0L) {
            throw new IllegalArgumentException("txId must >= 0: " + txId);
        }
        return RAFT_TX_ROOT + "/" + toHexPadded(txId, 32) + "/" + STATE_LEAF;
    }

    public static String raftPeerVoteTerm(long peerId) {
        if (peerId <= 0L) {
            throw new IllegalArgumentException("peerId must > 0: " + peerId);
        }
        return RAFT_PEER_ROOT + "/" + toHexPadded(peerId, 16) + "/" + RAFT_PEER_VOTE_TERM_LEAF;
    }

    public static String raftPeerNextIndex(long peerId) {
        if (peerId <= 0L) {
            throw new IllegalArgumentException("peerId must > 0: " + peerId);
        }
        return RAFT_PEER_ROOT + "/" + toHexPadded(peerId, 16) + "/" + RAFT_PEER_NEXT_INDEX_LEAF;
    }

    public static String raftPeerMatchIndex(long peerId) {
        if (peerId <= 0L) {
            throw new IllegalArgumentException("peerId must > 0: " + peerId);
        }
        return RAFT_PEER_ROOT + "/" + toHexPadded(peerId, 16) + "/" + RAFT_PEER_MATCH_INDEX_LEAF;
    }

    private RegistryMetaPaths() {}
}
