package com.q3lives.ds.cluster;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.q3lives.ds.cluster.stats.DbStatsRegistry;
import com.q3lives.ds.database.columnar.DsPartitionManager;

/**
 * 分布式数据库集群监控静态入口。
 *
 * <p>
 * 允许运行时注册 {@link DsServerDirectory}，供 p2p-sync 的 monitor HTTP 端点
 * 以及管理命令读取拓扑与分区占用情况。未注册目录时返回空快照。</p>
 */
public final class DsClusterMonitor {

    private static volatile DsServerDirectory directory;

    private DsClusterMonitor() {
    }

    /**
     * 注册分布式节点目录。
     */
    public static synchronized void registerDirectory(DsServerDirectory dir) {
        DsClusterMonitor.directory = dir;
    }

    /**
     * 获取当前注册的目录。
     */
    public static DsServerDirectory getDirectory() {
        return directory;
    }

    /**
     * 清空注册信息，主要用于测试。
     */
    public static synchronized void clear() {
        directory = null;
    }

    /**
     * 返回集群拓扑与分区占用的 JSON 友好 Map。
     */
    public static Map<String, Object> snapshotMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        DsServerDirectory dir = directory;
        if (dir == null) {
            root.put("ok", Boolean.FALSE);
            root.put("message", "DsServerDirectory not registered");
            root.put("servers", new ArrayList<Map<String, Object>>());
            root.put("localServerIds", asList(DsNodeContext.listServerIds()));
            return root;
        }

        root.put("ok", Boolean.TRUE);
        int[] serverIds = dir.listServerIds();
        if (serverIds == null) {
            serverIds = new int[0];
        }

        List<Map<String, Object>> servers = new ArrayList<>();
        for (int serverId : serverIds) {
            servers.add(serverSnapshot(dir, serverId));
        }
        root.put("servers", servers);
        root.put("localServerIds", asList(DsNodeContext.listServerIds()));
        return root;
    }

    private static Map<String, Object> serverSnapshot(DsServerDirectory dir, int serverId) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("serverId", serverId);
        DsServerDirectory.Endpoint ep = dir.endpoint(serverId);
        server.put("host", ep == null ? "" : ep.host());
        server.put("port", ep == null ? 0 : ep.port());
        server.put("queueSize", ep == null ? 0 : ep.queueSize());

        int[] replicas = dir.replicas(serverId);
        server.put("replicas", asList(replicas == null ? new int[0] : replicas));
        // P55：replicaLag 由常量占位升级为 DbStatsRegistry 聚合真值（各副本复制 lag 的最大值）。
        server.put("replicaLag", DbStatsRegistry.snapshot().replicaLag());

        DsPartitionManager manager = DsNodeContext.getManager(serverId);
        List<Map<String, Object>> partitions = new ArrayList<>();
        int partitionCount = manager == null ? 0 : manager.getPartitionCount();
        int occupied = 0;
        long totalBytes = 0L;
        for (int i = 0; i < partitionCount; i++) {
            File root = manager.getPartitionRoot(i);
            boolean initialized = manager.isPartitionInitialized(i);
            long bytes = directorySize(root);
            if (initialized || bytes > 0L) {
                occupied++;
            }
            totalBytes += bytes;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("partitionId", i);
            p.put("rootPath", root.getAbsolutePath());
            p.put("initialized", initialized);
            p.put("occupied", initialized || bytes > 0L);
            p.put("bytes", bytes);
            partitions.add(p);
        }
        server.put("partitionCount", partitionCount);
        server.put("occupiedPartitions", occupied);
        server.put("totalBytes", totalBytes);
        server.put("partitions", partitions);
        return server;
    }

    private static long directorySize(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return 0L;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return 0L;
        }
        long total = 0L;
        for (File f : files) {
            if (f.isFile()) {
                total += f.length();
            } else if (f.isDirectory()) {
                total += directorySize(f);
            }
        }
        return total;
    }

    private static List<Integer> asList(int[] values) {
        if (values == null) {
            return new ArrayList<>();
        }
        return Arrays.stream(values).boxed().collect(Collectors.toList());
    }
}
