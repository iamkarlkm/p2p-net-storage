package com.q3lives.ds.index.value;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.q3lives.ds.kv.DsSha256KV;
import com.q3lives.ds.util.DsDataUtil;

/**
 * Tag -> fileId 列表索引（链表头插）。
 *
 * <p>设计：</p>
 * <ul>
 *   <li>tagMap：tagHash(sha256(tag)) -> headNodeId（8B）</li>
 *   <li>nodeStore：nodeId -> nodeData，其中 nodeData 保存 {fileId,nextNodeId}</li>
 * </ul>
 *
 * <p>特性与约束：</p>
 * <ul>
 *   <li>写入：{@link #addTag(String, long)} 采用头插法，因此同一个 tag 的 fileId 顺序为“最近添加在前”。</li>
 *   <li>读取：{@link #getFilesByTag(String)} 从 head 开始遍历到链表末尾。</li>
 *   <li>去重：当前实现不做去重；同一 fileId 可以被重复添加到同一 tag 链表中。</li>
 * </ul>
 */
public class DsTagIndex {

    private final DsSha256KV tagMap;
    private final DsSha256KV nodeStore;

    /**
     * 创建一个 Tag 索引。
     *
     * <p>会在 rootDir 下创建 tag_map 与 tag_nodes 两个子目录分别存放 head 映射与链表节点。</p>
     */
    public DsTagIndex(String rootDir) throws IOException {
        this.tagMap = new DsSha256KV(rootDir + File.separator + "tag_map");
        this.nodeStore = new DsSha256KV(rootDir + File.separator + "tag_nodes");
    }

    /**
     * 将 fileId 添加到 tag 对应的链表中（头插）。
     * @param tag
     * @param fileId
     * @throws java.io.IOException
     */
    public void addTag(String tag, long fileId) throws IOException {
        byte[] tagHash = DsDataUtil.sha256(tag.getBytes(StandardCharsets.UTF_8));
        byte[] headBytes = tagMap.get(tagHash);
        long oldHeadId = 0L;
        if (headBytes != null && headBytes.length >= 8) {
            oldHeadId = DsDataUtil.loadLong(headBytes, 0);
        }

        byte[] nodeData = new byte[32];
        DsDataUtil.storeLong(nodeData, 0, fileId);
        DsDataUtil.storeLong(nodeData, 8, oldHeadId);

        byte[] nodeKey = DsDataUtil.sha256((tag + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
        long newNodeId = nodeStore.update(nodeKey, nodeData);

        byte[] newHeadBytes = new byte[8];
        DsDataUtil.storeLong(newHeadBytes, 0, newNodeId);
        tagMap.update(tagHash, newHeadBytes);
    }

    /**
     * 获取 tag 对应的全部 fileId（从链表头开始遍历）。
     */
    public List<Long> getFilesByTag(String tag) throws IOException {
        List<Long> fileIds = new ArrayList<>();
        byte[] tagHash = DsDataUtil.sha256(tag.getBytes(StandardCharsets.UTF_8));
        byte[] headBytes = tagMap.get(tagHash);
        if (headBytes == null || headBytes.length < 8) {
            return fileIds;
        }
        long currentNodeId = DsDataUtil.loadLong(headBytes, 0);

        while (currentNodeId > 0) {
            byte[] nodeData = nodeStore.getValueByIndexId(currentNodeId);
            if (nodeData == null || nodeData.length < 16) {
                break;
            }
            long fileId = DsDataUtil.loadLong(nodeData, 0);
            long nextNodeId = DsDataUtil.loadLong(nodeData, 8);
            fileIds.add(fileId);
            currentNodeId = nextNodeId;
        }

        return fileIds;
    }

    /**
     * 关闭底层存储资源。
     */
    public void close() {
        tagMap.close();
        nodeStore.close();
    }
}
