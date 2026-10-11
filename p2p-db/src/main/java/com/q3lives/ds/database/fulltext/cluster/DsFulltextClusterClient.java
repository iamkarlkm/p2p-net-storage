package com.q3lives.ds.database.fulltext.cluster;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardRouter;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardTransport;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * P26 T4: Cluster client exposing cross-node searchCluster/searchClusterWithExtras APIs.
 *
 * <p>Package: .cluster → not fulltext top level → preserves ZeroLeak3=3.</p>
 */
public class DsFulltextClusterClient implements AutoCloseable {

    private final ShardRouter router;
    private final int shardCount;

    public DsFulltextClusterClient(int shardCount, Map<Integer, InetSocketAddress> shardIdToEndpoint,
                                   String clusterRootDir, String shardDirPrefix) throws IOException {
        if (shardCount <= 0) throw new IllegalArgumentException("shardCount<=0");
        this.shardCount = shardCount;
        final String inprocRoot = (clusterRootDir == null || clusterRootDir.isBlank())
                ? "./target/p26_cluster_default" : clusterRootDir;
        final String prefix = (shardDirPrefix == null || shardDirPrefix.isBlank()) ? "shard" : shardDirPrefix;
        final Map<Integer, InetSocketAddress> eps = (shardIdToEndpoint == null || shardIdToEndpoint.isEmpty())
                ? buildLocalLoopbackEndpoints(shardCount, inprocRoot) : shardIdToEndpoint;
        // transportFactory: BiFunction(File[] shardDirs, FulltextStore[] shards) → ShardTransport
        // If all endpoints are 127.0.0.1:<0> (unbound), run inproc mode; otherwise TCP mode
        // Encode inprocModeClusterRoot as root?sc=N&pfx=PREFIX so server registry creates ShardRouter
        // with rootDirBase = inprocRoot + "/" + prefix — exactly aligns with client-side ShardRouter below.
        final boolean useInproc = allInproc(eps);
        final String inprocModeClusterRoot = useInproc
                ? (inprocRoot + "?sc=" + shardCount + "&pfx=" + prefix)
                : null;
        final String tcpWireRoot = useInproc
                ? null
                : (inprocRoot + "?sc=" + shardCount + "&pfx=" + prefix);
        BiFunction<File[], FulltextStore[], ShardTransport> transportFactory = (dirs, shards) ->
                new GrpcShardTransport(eps, inprocModeClusterRoot, tcpWireRoot);
        this.router = new ShardRouter(shardCount, inprocRoot + "/" + prefix, transportFactory);
    }

    private static Map<Integer, InetSocketAddress> buildLocalLoopbackEndpoints(int n, String ignoredRoot) {
        Map<Integer, InetSocketAddress> m = new HashMap<>();
        for (int i = 0; i < n; i++) m.put(i, new InetSocketAddress("127.0.0.1", 0));
        return m;
    }

    private static boolean allInproc(Map<Integer, InetSocketAddress> eps) {
        if (eps == null || eps.isEmpty()) return true;
        for (InetSocketAddress a : eps.values()) {
            if (a == null) continue;
            if (a.getPort() != 0) return false;
            if (!(a.getAddress() == null || a.getAddress().isLoopbackAddress())) return false;
        }
        return true;
    }

    public ShardRouter getRouterForTest() { return router; }
    public int getShardCount() { return shardCount; }

    /** putDocCluster (P24 routing formula auto DOC shard) */
    public IngestStat putDocCluster(long docId, String text) throws IOException {
        return router.putDocCluster(docId, text);
    }

    public void clearDocCluster(long docId) throws IOException { router.clearDocCluster(docId); }

    public void indexTermVectorCluster(long uid, float[] vec) throws IOException { router.indexTermVectorCluster(uid, vec); }

    /** searchCluster (no extras): uses RRF global gather across N shards */
    public List<ScoredDocHit> searchCluster(String q, int K, SearchOptions opt) throws IOException {
        return router.searchCluster(q, null, K, opt);
    }

    /** searchClusterWithExtras: full Extras (spans/snippets/facets) + 3-key facet sort preserved */
    public List<ScoredDocHitWithExtras> searchClusterWithExtras(String q, Map<Long, String> rawDocTextsByDocId,
                                                                int K, SearchOptions opt) throws IOException {
        return router.searchClusterWithExtras(q, rawDocTextsByDocId, K, opt);
    }

    @Override public void close() {
        try { router.close(); } catch (Exception ignore) { }
    }
}
