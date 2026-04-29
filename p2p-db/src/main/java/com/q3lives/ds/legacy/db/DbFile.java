package com.q3lives.ds.legacy.db;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.q3lives.ds.fs.FileMetadata;
import com.q3lives.ds.util.DsDataUtil;

/**
 * 分布式文件存储系统核心类。
 * 使用 DbSha256KV 作为底层存储，实现无限量的文件存储。
 * 
 * 架构：
 * 1. 文件内容存储： Content(SHA256) -> DbSha256KV (Value)
 * 2. 文件元数据存储： GlobalFileID(SHA256) -> FileMetadata (Serialized Object) -> DbSha256KV (Value)
 * 3. 目录树索引 (暂未实现完全树结构，仅通过 ParentID 关联)
 */
public class DbFile {
    
    private final DbSha256KV contentStore; // 存储文件内容 (CAS)
    private final DbSha256KV metaStore;    // 存储文件元数据 (ID -> Meta)
    private final DbTagIndex tagIndex;     // 标签索引 (Tag -> FileID List)
    
    public DbFile(String rootDir) throws IOException {
        // 使用两个独立的 KV 存储实例，分别对应内容和元数据目录
        this.contentStore = new DbSha256KV(rootDir + File.separator + "content");
        this.metaStore = new DbSha256KV(rootDir + File.separator + "meta");
        this.tagIndex = new DbTagIndex(rootDir + File.separator + "tags");
    }
    
    /**
     * 保存文件。
     * @param content 文件内容
     * @param metadata 文件元数据 (部分字段如 ID, Hash, Time 由内部自动生成或更新)
     * @return 更新后的 FileMetadata
     */
    public FileMetadata saveFile(byte[] content, FileMetadata metadata) throws IOException, InterruptedException {
        // 1. 计算内容哈希
        byte[] contentHashBytes = DsDataUtil.sha256(content);
        // 转为 Hex 字符串存入 Metadata
        StringBuilder hex = new StringBuilder();
        for (byte b : contentHashBytes) {
            hex.append(String.format("%02x", b));
        }
        String contentHashStr = hex.toString();
        
        // 2. 存储文件内容 (去重：如果 Hash 已存在，DbSha256KV 会处理吗？)
        // DbSha256KV 是基于 Key-Value 的。Key 是 ContentHash? 
        // 这里的 Key 应该是 ContentHash。Value 是 Content。
        // 这样可以实现基于内容的全局去重 (CAS)。
        
        // Key = ContentHash (32 bytes), Value = Content
        // 使用 update 方法，它会自动计算 Key 的 SHA256 作为索引哈希
        contentStore.update(contentHashBytes, content);
        
        // 3. 补全 Metadata
        if (metadata.globalFileId == null || metadata.globalFileId.isEmpty()) {
            metadata.globalFileId = UUID.randomUUID().toString();
        }
        
        metadata.basic.fileSize = content.length;
        metadata.storage.fileSizePhysical = content.length; // 暂未压缩
        metadata.basic.contentHash = contentHashStr;
        metadata.security.hashAlgorithm = "SHA-256";
        
        // 4. 存储 Metadata 分组
        // 使用 composite key: GlobalFileID + "_basic", "_time" 等
        saveMetadataGroup(metadata.globalFileId, "basic", metadata.basic);
        saveMetadataGroup(metadata.globalFileId, "time", metadata.time);
        saveMetadataGroup(metadata.globalFileId, "storage", metadata.storage);
        saveMetadataGroup(metadata.globalFileId, "security", metadata.security);
        saveMetadataGroup(metadata.globalFileId, "ext", metadata.ext);
        saveMetadataGroup(metadata.globalFileId, "tags", metadata.tags);
        
        // 5. 更新 Tag 索引
        // 将 GlobalFileID 关联到每个 Tag
        // 注意：DbTagIndex 存储的是 Long 类型的 FileID。
        // 但是我们的 FileID 是 UUID String。
        // 这意味着我们需要一个 String -> Long 的映射？
        // 或者 DbTagIndex 应该存储 String？
        // 如果存储 String，每个节点变大。
        // 鉴于 DbSha256KV 本身就是 Key -> Long (IndexID)，
        // 我们可以直接存储 metadata.globalFileId 对应的 IndexID 吗？
        // 不，globalFileId 对应的 IndexID 是分散在多个 Group 里的。
        // 我们需要一个稳定的 FileID -> Long 映射。
        // 或者简单点，我们把 UUID hash 成 Long？会有碰撞。
        
        // 方案：让 DbTagIndex 存储 String 类型的 FileID。
        // 修改 DbTagIndex，使其 Node 存储 UUID String (36 bytes + padding = 48 bytes)。
        // 或者，我们依然存储 Long，但是这个 Long 是 metadata "basic" 分组的存储位置 (IndexID)。
        // 这样通过 Tag -> IndexID -> BasicMeta -> GlobalFileID。
        
        // 获取 basic 分组的存储 IndexID
        // saveMetadataGroup 目前只 update，不返回 IndexID。
        // 我们修改 saveMetadataGroup 返回 IndexID。
        long basicIndexId = saveMetadataGroup(metadata.globalFileId, "basic", metadata.basic);
        
        if (metadata.tags != null && metadata.tags.tags != null) {
            for (Map.Entry<String, String> entry : metadata.tags.tags.entrySet()) {
                String tagKey = entry.getKey();
                String tagValue = entry.getValue();
                // 存储格式: key=value
                String fullTag = tagKey + "=" + tagValue;
                tagIndex.addTag(fullTag, basicIndexId);
            }
        }
        
        return metadata;
    }
    
    private long saveMetadataGroup(String globalFileId, String groupName, Object groupObj) throws IOException, InterruptedException {
        String compositeKey = globalFileId + "_" + groupName;
        byte[] keyBytes = compositeKey.getBytes(StandardCharsets.UTF_8);
        byte[] metaBytes = serializeObject(groupObj);
        return metaStore.update(keyBytes, metaBytes);
    }
    
    /**
     * 根据 Tag 查询文件列表。
     * @param tagKey 标签键
     * @param tagValue 标签值
     * @return 匹配的 BasicMeta 列表
     */
    public List<FileMetadata.BasicMeta> getFilesByTag(String tagKey, String tagValue) throws IOException, InterruptedException, ClassNotFoundException {
        List<FileMetadata.BasicMeta> results = new ArrayList<>();
        String fullTag = tagKey + "=" + tagValue;
        
        // 1. 获取所有匹配的 BasicMeta IndexID
        List<Long> basicIndexIds = tagIndex.getFilesByTag(fullTag);
        
        // 2. 根据 IndexID 直接读取 BasicMeta
        for (Long indexId : basicIndexIds) {
            // 注意：metaStore 是 DbSha256KV。
            // 我们可以直接通过 indexId 获取数据吗？
            // DbSha256KV.getData(indexId, "value") 可以获取 Value。
            byte[] metaBytes = metaStore.getValueByIndexId(indexId);
            if (metaBytes != null) {
                Object obj = deserializeObject(metaBytes);
                if (obj instanceof FileMetadata.BasicMeta) {
                    results.add((FileMetadata.BasicMeta) obj);
                }
            }
        }
        
        return results;
    }

    /**
     * 获取完整的文件元数据。
     * @param globalFileId 全局文件 ID
     */
    public FileMetadata getMetadata(String globalFileId) throws IOException, InterruptedException, ClassNotFoundException {
        FileMetadata metadata = new FileMetadata();
        metadata.globalFileId = globalFileId;
        
        FileMetadata.BasicMeta basic = (FileMetadata.BasicMeta) loadMetadataGroup(globalFileId, "basic");
        if (basic == null) return null; // 如果 basic 不存在，认为文件不存在
        
        metadata.basic = basic;
        
        FileMetadata.TimeMeta time = (FileMetadata.TimeMeta) loadMetadataGroup(globalFileId, "time");
        if (time != null) metadata.time = time;
        
        FileMetadata.StorageMeta storage = (FileMetadata.StorageMeta) loadMetadataGroup(globalFileId, "storage");
        if (storage != null) metadata.storage = storage;
        
        FileMetadata.SecurityMeta security = (FileMetadata.SecurityMeta) loadMetadataGroup(globalFileId, "security");
        if (security != null) metadata.security = security;
        
        FileMetadata.ExtMeta ext = (FileMetadata.ExtMeta) loadMetadataGroup(globalFileId, "ext");
        if (ext != null) metadata.ext = ext;
        
        // Tags 不再存储在 metaStore 中，需要重构 TagsMeta 或者从 TagIndex 反查？
        // 通常 getMetadata 不需要返回所有 Tags，或者我们需要一个 TagStore 来反向存储 FileID -> Tags。
        // 为了简单，我们暂时不返回 Tags，或者如果用户需要 Tags，
        // 我们应该保留一个 tags group 存储 FileID -> Tags 映射，
        // 同时使用 TagIndex 存储 Tag -> FileID 映射。
        // 为了保持一致性，我们恢复 saveMetadataGroup("tags")，但只用于 getMetadata，不用于搜索。
        // 或者我们可以查询 TagIndex？不行，TagIndex 是 Tag -> FileID。
        // 所以我们还是需要存储 tags group。
        
        FileMetadata.TagsMeta tags = (FileMetadata.TagsMeta) loadMetadataGroup(globalFileId, "tags");
        if (tags != null) metadata.tags = tags;
        
        return metadata;
    }
    
    /**
     * 仅获取基础元数据 (用于列表展示等高频轻量场景)。
     */
    public FileMetadata.BasicMeta getBasicMetadata(String globalFileId) throws IOException, InterruptedException, ClassNotFoundException {
        return (FileMetadata.BasicMeta) loadMetadataGroup(globalFileId, "basic");
    }
    
    private Object loadMetadataGroup(String globalFileId, String groupName) throws IOException, InterruptedException, ClassNotFoundException {
        String compositeKey = globalFileId + "_" + groupName;
        byte[] keyBytes = compositeKey.getBytes(StandardCharsets.UTF_8);
        byte[] metaBytes = metaStore.get(keyBytes);
        if (metaBytes == null) return null;
        return deserializeObject(metaBytes);
    }
    
    /**
     * 获取文件内容。
     * @param contentHashStr 内容哈希 (Hex String)
     */
    public byte[] getFileContent(String contentHashStr) throws IOException, InterruptedException {
        byte[] contentHashBytes = hexStringToByteArray(contentHashStr);
        // Key is ContentHash
        // ContentHashBytes IS the key used in saveFile
        return contentStore.get(contentHashBytes);
    }
    
    /**
     * 根据 FileID 获取文件内容（便捷方法）。
     */
    public byte[] getFileContentById(String globalFileId) throws IOException, InterruptedException, ClassNotFoundException {
        FileMetadata.BasicMeta basic = getBasicMetadata(globalFileId);
        if (basic == null) return null;
        return getFileContent(basic.contentHash);
    }
    
    // --- 辅助方法 ---
    
    private byte[] serializeObject(Object obj) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
            return bos.toByteArray();
        }
    }
    
    private Object deserializeObject(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return ois.readObject();
        }
    }
    
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int d1 = Character.digit(s.charAt(i), 16);
            int d2 = Character.digit(s.charAt(i+1), 16);
            data[i / 2] = (byte) ((d1 << 4) + d2);
        }
        return data;
    }
}
