package com.q3lives.ds.fs;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件元数据类，封装了混合云分布式存储系统的核心属性。
 * 采用分组解耦设计，支持按场景独立存储和查询。
 */
public class FileMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    public String globalFileId; // 全局唯一 ID (UUIDv4) - 主键，不在具体组内序列化，作为 Key 的一部分

    // --- 分组定义 ---

    public BasicMeta basic = new BasicMeta();
    public TimeMeta time = new TimeMeta();
    public StorageMeta storage = new StorageMeta();
    public SecurityMeta security = new SecurityMeta();
    public ExtMeta ext = new ExtMeta();
    public TagsMeta tags = new TagsMeta();

    // 1. 基础信息组 (高频查询，如列表展示)
    public static class BasicMeta implements Serializable {
        private static final long serialVersionUID = 1L;
        public String localFileId;
        public String parentDirId;
        public String filePathVirtual;
        public String fileName;
        public String displayName; // 显示文件名 (可为空)
        public FileType fileType;
        public String fileSubtype;
        public long fileSize;
        public String contentHash;
        public Visibility visibility;
    }

    // 2. 时间维度组 (中频查询，如排序、清理)
    public static class TimeMeta implements Serializable {
        private static final long serialVersionUID = 1L;
        public long createTime;
        public long modifyTime;
        public long metaModifyTime;
        public long accessTime;
        public long realUseTime;
        public long syncTime;
        public long expireTime;
        public long retainUntil;
        public long birthTime; // 原始创建时间
        public long lcTime; // 本地创建时间
        public long deleteTime;
    }

    // 3. 存储分层与副本组 (运维、同步使用)
    public static class StorageMeta implements Serializable {
        private static final long serialVersionUID = 1L;
        public List<String> filePathPhysical;
        public String shardId;
        public long fileSizePhysical;
        public int blockSize;
        public int blockCount;
        public StorageTier storageTier;
        public int replicaCount;
        public String ecPolicy;
        public String compressionAlg;
        public double compressionRatio;
        public SyncStatus syncStatus;
    }

    // 4. 安全与权限组 (鉴权使用)
    public static class SecurityMeta implements Serializable {
        private static final long serialVersionUID = 1L;
        public String ownerUid;
        public String ownerGid;
        public int permissionMode;
        public boolean immutable;
        public boolean appendOnly;
        public String hashAlgorithm;
        public String encryptAlg;
        public String encryptKeyId;
    }

    // 5. 业务扩展与监控组 (低频查询，可能体积较大)
    public static class ExtMeta implements Serializable {
        private static final long serialVersionUID = 1L;
        public Map<String, Object> extAttributes = new HashMap<>();
        public int version;
        public String versionId;
        public boolean queryable;
        public List<String> dependFiles;
        
        public HealthStatus healthStatus;
        public int errorCount;
        public int accessCount;
        public int hotScore;
        public double cost;
        public String auditLogId;
        public boolean deleteMark;
        
        public String source; // 来源 (微信/QQ/飞书/网络/本机)
        public String sourceUrl;
    }

    // 6. 标签组 (独立存储，支持多维度业务查询)
    public static class TagsMeta implements Serializable {
        private static final long serialVersionUID = 1L;
        public Map<String, String> tags = new HashMap<>();
    }

    // 枚举定义
    public enum FileType { NORMAL, DIRECTORY, SYMLINK, OBJECT, SHARD }
    public enum StorageTier { HOT, WARM, COLD, ARCHIVE, CLOUD_NATIVE, LOCAL_SSD, LOCAL_HDD }
    public enum SyncStatus { NOT_SYNCED, SYNCING, SYNCED, FAILED }
    public enum HealthStatus { NORMAL, DEGRADED, CORRUPTED, REPAIRING }
    public enum Visibility { GLOBAL, PUBLIC, GROUP, PRIVATE }
}
