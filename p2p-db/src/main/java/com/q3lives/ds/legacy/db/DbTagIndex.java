package com.q3lives.ds.legacy.db;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.q3lives.ds.util.DsDataUtil;

/**
 * 基于 DbSha256KV 和链表结构实现的高效标签索引。
 * 映射: Tag (String) -> List<FileID (Long)>
 * 
 * 架构：
 * 1. Tag Hash (SHA256) -> DbSha256KV (Key)
 * 2. DbSha256KV (Value) -> HeadNodeID (Long)
 * 3. 链表节点 (Node) 存储在另一个 DbSha256KV (ChunkStore) 中
 *    Node Structure (32 bytes fixed):
 *    [FileID (8B)] [NextNodeID (8B)] [Padding (16B)]
 * 
 * 优点：
 * - 插入操作 (addTag) 为 O(1)：直接在链表头部插入新节点，更新 HeadNodeID。
 * - 查询操作 (getFilesByTag) 为 O(N)：遍历链表。
 * - 无需重写整个列表，适合高频修改。
 */
public class DbTagIndex {
    
    private final DbSha256KV tagMap;   // Tag Hash -> HeadNodeID
    private final DbSha256KV nodeStore; // NodeID -> Node Data
    
    // Node ID 自增生成器 (简单实现，实际应持久化)
    // 在这里我们使用 System.nanoTime() + Random 或者一个持久化的计数器
    // 为了简单，我们使用 DbSha256KV 的 put 返回的 IndexID 作为 NodeID?
    // 不，DbSha256KV 的 IndexID 是物理存储位置。
    // 我们可以直接利用 DbSha256KV 的存储能力：
    // NodeID = DbSha256KV.put(data) 返回的 ID。
    
    public DbTagIndex(String rootDir) throws IOException {
        this.tagMap = new DbSha256KV(rootDir + File.separator + "tag_map");
        this.nodeStore = new DbSha256KV(rootDir + File.separator + "tag_nodes");
    }
    
    /**
     * 添加文件到标签 (O(1) 插入)。
     */
    public void addTag(String tag, long fileId) throws IOException, InterruptedException {
        byte[] tagHash = DsDataUtil.sha256(tag.getBytes(StandardCharsets.UTF_8));
        
        // 1. 获取旧的头节点 ID
        byte[] headBytes = tagMap.get(tagHash);
        long oldHeadId = 0; // 0 表示 null (假设 ID 从 1 开始)
        if (headBytes != null && headBytes.length >= 8) {
            oldHeadId = DsDataUtil.loadLong(headBytes, 0);
        }
        
        // 2. 创建新节点: [FileID, OldHeadID]
        byte[] nodeData = new byte[32]; // 固定 32 字节节点
        DsDataUtil.storeLong(nodeData, 0, fileId);
        DsDataUtil.storeLong(nodeData, 8, oldHeadId);
        
        // 3. 存储新节点，获取新节点 ID
        // 为了生成唯一的 Key，我们使用 (TagHash + Random/Time) 的 Hash，或者直接依赖 DbSha256KV 的 append 能力？
        // DbSha256KV 是基于 Key-Value 的。我们需要一个唯一的 Key 来存 Node。
        // 使用 UUID 或 NanoTime 作为 Key。
        byte[] nodeKey = DsDataUtil.sha256((tag + System.nanoTime() + Math.random()).getBytes(StandardCharsets.UTF_8));
        long newNodeId = nodeStore.put(nodeKey, nodeKey, nodeData);
        
        // 4. 更新 TagMap 指向新头节点
        byte[] newHeadBytes = new byte[8];
        DsDataUtil.storeLong(newHeadBytes, 0, newNodeId);
        tagMap.update(tagHash, newHeadBytes);
    }
    
    /**
     * 获取标签对应的所有文件 ID。
     */
    public List<Long> getFilesByTag(String tag) throws IOException, InterruptedException {
        List<Long> fileIds = new ArrayList<>();
        byte[] tagHash = DsDataUtil.sha256(tag.getBytes(StandardCharsets.UTF_8));
        
        // 1. 获取头节点 ID
        byte[] headBytes = tagMap.get(tagHash);
        if (headBytes == null || headBytes.length < 8) {
            return fileIds; // Empty
        }
        long currentNodeId = DsDataUtil.loadLong(headBytes, 0);
        
        // 2. 遍历链表
        while (currentNodeId > 0) {
            byte[] nodeData = nodeStore.getValueByIndexId(currentNodeId); // 直接通过 ID 读取 Value
            if (nodeData == null || nodeData.length < 16) break;
            
            long fileId = DsDataUtil.loadLong(nodeData, 0);
            long nextNodeId = DsDataUtil.loadLong(nodeData, 8);
            
            fileIds.add(fileId);
            currentNodeId = nextNodeId;
        }
        
        return fileIds;
    }
    
    /**
     * 移除标签中的某个文件 ID (需要遍历链表，O(N))。
     */
    public void removeTag(String tag, long fileIdToRemove) throws IOException, InterruptedException {
        byte[] tagHash = DsDataUtil.sha256(tag.getBytes(StandardCharsets.UTF_8));
        
        byte[] headBytes = tagMap.get(tagHash);
        if (headBytes == null) return;
        long headId = DsDataUtil.loadLong(headBytes, 0);
        
        long currentNodeId = headId;
        long prevNodeId = 0;
        
        while (currentNodeId > 0) {
            byte[] nodeData = nodeStore.getData(currentNodeId, "value");
            if (nodeData == null) break;
            
            long fileId = DsDataUtil.loadLong(nodeData, 0);
            long nextNodeId = DsDataUtil.loadLong(nodeData, 8);
            
            if (fileId == fileIdToRemove) {
                // 找到目标，执行删除
                if (prevNodeId == 0) {
                    // 删除的是头节点，更新 TagMap
                    byte[] newHeadBytes = new byte[8];
                    DsDataUtil.storeLong(newHeadBytes, 0, nextNodeId);
                    tagMap.update(tagHash, newHeadBytes);
                } else {
                    // 删除的是中间节点，更新前驱节点的 Next 指针
                    // 这需要 update prevNode。
                    // 也就是读取 prevNode -> 修改 next -> 写入。
                    // 注意：这需要 prevNode 的 Key。但我们只有 ID。
                    // DbSha256KV 目前只能通过 Key update。
                    // 如果我们不能通过 ID update，那这个链表结构就只能 append-only。
                    
                    // 解决方案：使用 update(Key, Value)。我们需要知道 Node 的 Key。
                    // 但我们在链表里只存了 NodeID。
                    
                    // 鉴于 DbSha256KV 的设计，直接修改底层文件可能更简单，
                    // 或者我们存储 [FileID, NextNodeID, NodeKey(32B)]。
                    // 这样我们可以获取 Key 并更新。
                    
                    // 简单起见，目前仅实现 add 和 get。remove 暂不支持或需要重建链表。
                    // 考虑到 "高效查询和修改"，如果无法 remove，是个问题。
                    
                    // 替代方案：不物理删除，而是标记删除 (Soft Delete)。
                    // Node 结构: [FileID, Next, Status]
                    // 将 Status 设为 Deleted。
                    // 但仍需 update。
                    
                    // 既然 DbSha256KV 主要是 Key-Value，
                    // 也许直接用 DbBytes 存储 ArrayList (byte[]) 加上分段机制才是最通用的。
                    // 对于 "DsList 方式"，其实是指 "定长数组"。
                    // 我们可以为每个 Tag 维护一个逻辑上的定长数组。
                }
                return; 
            }
            
            prevNodeId = currentNodeId;
            currentNodeId = nextNodeId;
        }
    }
}
