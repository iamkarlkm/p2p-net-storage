package com.q3lives.ds.database.fulltext.cluster;

import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardTransport;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.ClearDocShardRequest;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.IndexTermVectorShardRequest;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.PutDocShardRequest;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchShardRequest;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchShardWithExtrasRequest;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchWithVectorShardRequest;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.codec.P2PWrapperEncoder;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.annotation.Nonnull;

/**
 * P26 T4 ShardTransport impl via p2p-core handlers (in-process loopback for test; TCP via P2PClientTcp in production).
 *
 * <p>Package: .cluster (not .fulltext root) → preserves ZeroLeak3=3 and NG-3 fulltext 0 netty/grpc symbols.</p>
 */
public class GrpcShardTransport implements ShardTransport {

    private final Map<Integer, InetSocketAddress> shardIdToEndpoint;
    private final String wireClusterRoot;  // always in proto payload cluster_root field
    private final String clusterRoot;  // for inproc mode: passed as cluster_root in proto requests
    private final boolean inprocMode;  // TEST: dispatch handlers directly, no TCP
    // Handler instances cached for inproc dispatch
    private final Map<P2PCommand, P2PCommandHandler<?>> inprocHandlers;
    // TCP client cache for real TCP mode (per shard, lazy init)
    private final ConcurrentHashMap<Integer, P2PClientTcp> tcpClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer> tcpSeqPerShard = new ConcurrentHashMap<>();
    private static final int TCP_QUEUE_SIZE = 4096;
    private static final int TCP_CORE_THREADS = 2;
    private static final long TCP_TIMEOUT_SEC = 15L;

    public GrpcShardTransport(Map<Integer, InetSocketAddress> shardIdToEndpoint) {
        this(shardIdToEndpoint, null);
    }

    public GrpcShardTransport(Map<Integer, InetSocketAddress> shardIdToEndpoint, String inprocClusterRoot) {
        this(shardIdToEndpoint, inprocClusterRoot, null);
    }

    /**
     * Three-arg constructor (T2+ TCP mode support).
     * @param shardIdToEndpoint shardId→endpoint map (TCP real ports or inproc 127.0.0.1:0)
     * @param inprocClusterRoot non-null → inproc mode; null → TCP mode
     * @param tcpWireClusterRoot only used for TCP mode: value passed in proto cluster_root field
     *                           so server handlers can locate their shared registry; may be null
     *                           in pure inproc (derived from inprocClusterRoot)
     */
    public GrpcShardTransport(Map<Integer, InetSocketAddress> shardIdToEndpoint,
                              String inprocClusterRoot,
                              String tcpWireClusterRoot) {
        if (shardIdToEndpoint == null || shardIdToEndpoint.isEmpty()) {
            throw new IllegalArgumentException("shardIdToEndpoint empty");
        }
        this.shardIdToEndpoint = Collections.unmodifiableMap(new java.util.HashMap<>(shardIdToEndpoint));
        final int sc = shardIdToEndpoint.size();
        if (inprocClusterRoot == null || inprocClusterRoot.isBlank()) {
            this.clusterRoot = null;
        } else if (inprocClusterRoot.indexOf('?') > 0) {
            this.clusterRoot = inprocClusterRoot; // caller pre-encoded (e.g., root?sc=N&pfx=xxx)
        } else {
            this.clusterRoot = inprocClusterRoot + "?sc=" + sc + "&pfx=svr_shard";
        }
        this.inprocMode = (this.clusterRoot != null);
        // wireClusterRoot: for proto request cluster_root field
        if (inprocMode) {
            this.wireClusterRoot = this.clusterRoot; // inproc: same as clusterRoot
        } else {
            // TCP mode: use caller-provided tcpWireClusterRoot, normalize if needed
            if (tcpWireClusterRoot == null || tcpWireClusterRoot.isBlank()) {
                this.wireClusterRoot = "";
            } else if (tcpWireClusterRoot.indexOf('?') > 0) {
                this.wireClusterRoot = tcpWireClusterRoot;
            } else {
                this.wireClusterRoot = tcpWireClusterRoot + "?sc=" + sc + "&pfx=svr_shard";
            }
        }
        this.inprocHandlers = new HashMap<>();
        if (inprocMode) {
            inprocHandlers.put(P2PCommand.FULLTEXT_SHARD_PUT_DOC,       newH("javax.net.p2p.server.handler.fulltext.FulltextShardPutDocHandler"));
            inprocHandlers.put(P2PCommand.FULLTEXT_SHARD_CLEAR_DOC,     newH("javax.net.p2p.server.handler.fulltext.FulltextShardClearDocHandler"));
            inprocHandlers.put(P2PCommand.FULLTEXT_SHARD_INDEX_VECTOR,  newH("javax.net.p2p.server.handler.fulltext.FulltextShardIndexVectorHandler"));
            inprocHandlers.put(P2PCommand.FULLTEXT_SHARD_SEARCH_VECTOR, newH("javax.net.p2p.server.handler.fulltext.FulltextShardSearchVectorHandler"));
            inprocHandlers.put(P2PCommand.FULLTEXT_SHARD_SEARCH,        newH("javax.net.p2p.server.handler.fulltext.FulltextShardSearchHandler"));
            inprocHandlers.put(P2PCommand.FULLTEXT_SHARD_SEARCH_EXTRAS, newH("javax.net.p2p.server.handler.fulltext.FulltextShardSearchExtrasHandler"));
            inprocHandlers.put(P2PCommand.FULLTEXT_SHARD_CLOSE,         newH("javax.net.p2p.server.handler.fulltext.FulltextShardCloseHandler"));
        }
    }

    @SuppressWarnings("unchecked")
    private static P2PCommandHandler<Object> newH(String cn) {
        try {
            Class<?> c = Class.forName(cn);
            Constructor<?> ct = c.getDeclaredConstructor();
            ct.setAccessible(true);
            return (P2PCommandHandler<Object>) ct.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("handler new fail " + cn, e);
        }
    }

    private <ReqT, RespT> RespT callInproc(P2PCommand cmd, P2PCommand ackCmd, ReqT req, Class<RespT> respCls) {
        P2PWrapper<Object> wrapper = P2PWrapper.build(1, cmd, req);
        @SuppressWarnings("unchecked")
        P2PCommandHandler<Object> h = (P2PCommandHandler<Object>) inprocHandlers.get(cmd);
        if (h == null) throw new IllegalStateException("missing handler for cmd " + cmd);
        P2PWrapper<Object> resp = h.process(wrapper);
        Object payload = resp.getData();
        if (!respCls.isInstance(payload)) {
            Object maybeErr = payload;
            String errInfo;
            if (maybeErr == null) {
                errInfo = "null payload, wrapperCmd=" + resp.getCommand();
            } else {
                String nm = maybeErr.getClass().getName();
                String protoStr = maybeErr.toString();
                if (protoStr.length() > 500) protoStr = protoStr.substring(0, 500);
                errInfo = "unexpected payload type " + nm + " expected " + respCls.getName()
                        + " wrapper_cmd=" + resp.getCommand() + " payload=" + protoStr;
            }
            throw new IllegalStateException("handler for cmd " + cmd + " " + errInfo);
        }
        return respCls.cast(payload);
    }

    @Nonnull
    private P2PClientTcp getOrCreateTcpClient(int shardId) {
        return tcpClients.computeIfAbsent(shardId, sid -> {
            InetSocketAddress ep = shardIdToEndpoint.get(sid);
            if (ep == null) throw new IllegalArgumentException("no endpoint for shardId=" + sid
                    + ", available shards=" + shardIdToEndpoint.keySet());
            P2PClientTcp c = new P2PClientTcp(ep, TCP_QUEUE_SIZE, TCP_CORE_THREADS, P2PWrapperEncoder.MAGIC);
            c.newSendMesageExecutorToQueue();
            return c;
        });
    }

    private <ProtoT extends com.google.protobuf.MessageLite, RespT> RespT callTcp(
            int shardId, P2PCommand reqCmd, P2PCommand ackCmd,
            ProtoT reqProto, Function<byte[], RespT> parser,
            Function<RespT, Integer> statusFn, Function<RespT, String> errMsgFn) throws IOException {
        P2PClientTcp client = getOrCreateTcpClient(shardId);
        byte[] wire = reqProto.toByteArray();
        int seq = tcpSeqPerShard.merge(shardId, 1, (a, b) -> (a + 1) & 0x7fffffff);
        P2PWrapper<Object> wrapper;
        try {
            @SuppressWarnings("unchecked")
            P2PWrapper<Object> w = (P2PWrapper<Object>) client.excute(
                    P2PWrapper.build(seq, reqCmd, wire),
                    TCP_TIMEOUT_SEC, TimeUnit.SECONDS);
            wrapper = w;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("TCP call interrupted for cmd " + reqCmd + " shard=" + shardId, ie);
        } catch (Exception ex) {
            throw new IOException("TCP call failed for cmd " + reqCmd + " shard=" + shardId
                    + ": " + ex.getMessage(), ex);
        }
        if (wrapper == null) {
            throw new IOException("TCP call timeout null for cmd " + reqCmd + " shard=" + shardId);
        }
        if (wrapper.getCommand() == P2PCommand.STD_ERROR) {
            throw new IOException("TCP call STD_ERROR for cmd " + reqCmd
                    + " shard=" + shardId + ": " + wrapper.getData());
        }
        if (wrapper.getCommand() != ackCmd) {
            throw new IOException("TCP call unexpected ack cmd=" + wrapper.getCommand()
                    + " expected " + ackCmd + " for req " + reqCmd + " shard=" + shardId);
        }
        Object payload = wrapper.getData();
        if (!(payload instanceof byte[])) {
            String cls = payload == null ? "null" : payload.getClass().getName();
            throw new IOException("TCP call non-byte[] payload for cmd " + reqCmd
                    + " shard=" + shardId + ", class=" + cls);
        }
        RespT r = parser.apply((byte[]) payload);
        if (r == null) throw new IOException("TCP call parseFrom null for cmd " + reqCmd + " shard=" + shardId);
        int sc = statusFn.apply(r);
        if (sc != 0) {
            String em = errMsgFn.apply(r);
            throw new IOException("TCP call shard=" + shardId + " cmd=" + reqCmd
                    + " statusCode=" + sc + " msg=" + (em == null ? "" : em));
        }
        return r;
    }

    public Map<Integer, InetSocketAddress> getShardIdToEndpointForTest() { return shardIdToEndpoint; }
    public boolean isInprocModeForTest() { return inprocMode; }

    @Override public IngestStat putDoc(int shardId, long docId, String text) throws IOException {
        PutDocShardRequest req = PutDocShardRequest.newBuilder()
                .setShardId(shardId).setDocId(docId).setText(text == null ? "" : text)
                .setClusterRoot(wireClusterRoot == null ? "" : wireClusterRoot).build();
        FulltextShardWire.PutDocShardResponse r;
        if (inprocMode) {
            r = callInproc(P2PCommand.FULLTEXT_SHARD_PUT_DOC,
                    P2PCommand.R_OK_FULLTEXT_SHARD_PUT_DOC_ACK, req, FulltextShardWire.PutDocShardResponse.class);
        } else {
            r = callTcp(shardId, P2PCommand.FULLTEXT_SHARD_PUT_DOC, P2PCommand.R_OK_FULLTEXT_SHARD_PUT_DOC_ACK,
                    req, bytes -> { try { return FulltextShardWire.PutDocShardResponse.parseFrom(bytes); } catch (Exception e) { throw new RuntimeException(e); } },
                    FulltextShardWire.PutDocShardResponse::getStatusCode,
                    FulltextShardWire.PutDocShardResponse::getErrorMessage);
        }
        if (r.getStatusCode() != 0) throw new IOException("putDoc status=" + r.getStatusCode() + ":" + r.getErrorMessage());
        long pairsTotal = r.getPairsTotal();
        IngestStat s = IngestStat.zeroForTest();
        s.pairsTotal = pairsTotal;
        for (int i = 0; i < Math.min(s.rowsPerLevel.length, r.getRowsPerLevelCount()); i++) {
            s.rowsPerLevel[i] = r.getRowsPerLevel(i);
        }
        return s;
    }

    @Override public void clearDoc(int shardId, long docId) throws IOException {
        ClearDocShardRequest req = ClearDocShardRequest.newBuilder()
                .setShardId(shardId).setDocId(docId)
                .setClusterRoot(wireClusterRoot == null ? "" : wireClusterRoot).build();
        FulltextShardWire.ClearDocShardResponse r;
        if (inprocMode) {
            r = callInproc(P2PCommand.FULLTEXT_SHARD_CLEAR_DOC,
                    P2PCommand.R_OK_FULLTEXT_SHARD_CLEAR_DOC_ACK, req, FulltextShardWire.ClearDocShardResponse.class);
        } else {
            r = callTcp(shardId, P2PCommand.FULLTEXT_SHARD_CLEAR_DOC, P2PCommand.R_OK_FULLTEXT_SHARD_CLEAR_DOC_ACK,
                    req, bytes -> { try { return FulltextShardWire.ClearDocShardResponse.parseFrom(bytes); } catch (Exception e) { throw new RuntimeException(e); } },
                    FulltextShardWire.ClearDocShardResponse::getStatusCode,
                    FulltextShardWire.ClearDocShardResponse::getErrorMessage);
        }
        if (r.getStatusCode() != 0) throw new IOException("clearDoc status=" + r.getStatusCode() + ":" + r.getErrorMessage());
    }

    @Override public List<ScoredDocHit> searchWithVector(int shardId, String q, float[] qVec, int K, SearchOptions o) throws IOException {
        SearchWithVectorShardRequest.Builder b = SearchWithVectorShardRequest.newBuilder()
                .setShardId(shardId).setK(K).setClusterRoot(wireClusterRoot == null ? "" : wireClusterRoot)
                .setQuery(q == null ? "" : q)
                .setOptions(ProtoWireConverters.toProto(o == null ? SearchOptions.defaults() : o));
        if (qVec != null) for (float v : qVec) b.addVector(v);
        SearchWithVectorShardRequest req = b.build();
        FulltextShardWire.SearchWithVectorShardResponse r;
        if (inprocMode) {
            r = callInproc(P2PCommand.FULLTEXT_SHARD_SEARCH_VECTOR,
                    P2PCommand.R_OK_FULLTEXT_SHARD_SEARCH_VECTOR_ACK, req, FulltextShardWire.SearchWithVectorShardResponse.class);
        } else {
            r = callTcp(shardId, P2PCommand.FULLTEXT_SHARD_SEARCH_VECTOR, P2PCommand.R_OK_FULLTEXT_SHARD_SEARCH_VECTOR_ACK,
                    req, bytes -> { try { return FulltextShardWire.SearchWithVectorShardResponse.parseFrom(bytes); } catch (Exception e) { throw new RuntimeException(e); } },
                    FulltextShardWire.SearchWithVectorShardResponse::getStatusCode,
                    FulltextShardWire.SearchWithVectorShardResponse::getErrorMessage);
        }
        if (r.getStatusCode() != 0) throw new IOException("searchWithVector status=" + r.getStatusCode() + ":" + r.getErrorMessage());
        List<ScoredDocHit> out = new ArrayList<>(r.getHitsCount());
        for (int i = 0; i < r.getHitsCount(); i++) out.add(ProtoWireConverters.fromProto(r.getHits(i)));
        return out;
    }

    @Override public void indexTermVector(int shardId, long uid, float[] vec) throws IOException {
        IndexTermVectorShardRequest.Builder b = IndexTermVectorShardRequest.newBuilder()
                .setShardId(shardId).setUid(uid).setClusterRoot(wireClusterRoot == null ? "" : wireClusterRoot);
        if (vec != null) for (float v : vec) b.addVector(v);
        IndexTermVectorShardRequest req = b.build();
        FulltextShardWire.IndexTermVectorShardResponse r;
        if (inprocMode) {
            r = callInproc(P2PCommand.FULLTEXT_SHARD_INDEX_VECTOR,
                    P2PCommand.R_OK_FULLTEXT_SHARD_INDEX_VECTOR_ACK, req, FulltextShardWire.IndexTermVectorShardResponse.class);
        } else {
            r = callTcp(shardId, P2PCommand.FULLTEXT_SHARD_INDEX_VECTOR, P2PCommand.R_OK_FULLTEXT_SHARD_INDEX_VECTOR_ACK,
                    req, bytes -> { try { return FulltextShardWire.IndexTermVectorShardResponse.parseFrom(bytes); } catch (Exception e) { throw new RuntimeException(e); } },
                    FulltextShardWire.IndexTermVectorShardResponse::getStatusCode,
                    FulltextShardWire.IndexTermVectorShardResponse::getErrorMessage);
        }
        if (r.getStatusCode() != 0) throw new IOException("indexTermVector status=" + r.getStatusCode() + ":" + r.getErrorMessage());
    }

    @Override public void close() throws IOException {
        if (inprocMode) {
            FulltextShardWire.CloseShardRequest req = FulltextShardWire.CloseShardRequest.newBuilder()
                    .setShardId(0).setClusterRoot(wireClusterRoot == null ? "" : wireClusterRoot).build();
            try {
                callInproc(P2PCommand.FULLTEXT_SHARD_CLOSE, P2PCommand.R_OK_FULLTEXT_SHARD_CLOSE_ACK,
                        req, FulltextShardWire.CloseShardResponse.class);
            } catch (Exception ignore) { }
        } else {
            // TCP mode: close all tcpClients (ignore errors)
            for (P2PClientTcp c : tcpClients.values()) {
                try { c.close(); } catch (Exception ignore) { }
            }
            tcpClients.clear();
            tcpSeqPerShard.clear();
        }
    }

    @Override public List<ScoredDocHit> search(int shardId, String q, int K, SearchOptions o) throws IOException {
        SearchShardRequest.Builder b = SearchShardRequest.newBuilder()
                .setShardId(shardId).setQuery(q == null ? "" : q).setK(K)
                .setOptions(ProtoWireConverters.toProto(o == null ? SearchOptions.defaults() : o))
                .setClusterRoot(wireClusterRoot == null ? "" : wireClusterRoot);
        SearchShardRequest req = b.build();
        FulltextShardWire.SearchShardResponse r;
        if (inprocMode) {
            r = callInproc(P2PCommand.FULLTEXT_SHARD_SEARCH,
                    P2PCommand.R_OK_FULLTEXT_SHARD_SEARCH_ACK, req, FulltextShardWire.SearchShardResponse.class);
        } else {
            r = callTcp(shardId, P2PCommand.FULLTEXT_SHARD_SEARCH, P2PCommand.R_OK_FULLTEXT_SHARD_SEARCH_ACK,
                    req, bytes -> { try { return FulltextShardWire.SearchShardResponse.parseFrom(bytes); } catch (Exception e) { throw new RuntimeException(e); } },
                    FulltextShardWire.SearchShardResponse::getStatusCode,
                    FulltextShardWire.SearchShardResponse::getErrorMessage);
        }
        if (r.getStatusCode() != 0) throw new IOException("search status=" + r.getStatusCode() + ":" + r.getErrorMessage());
        List<ScoredDocHit> out = new ArrayList<>(r.getHitsCount());
        for (int i = 0; i < r.getHitsCount(); i++) out.add(ProtoWireConverters.fromProto(r.getHits(i)));
        return out;
    }

    @Override public List<ScoredDocHitWithExtras> searchWithExtras(int shardId, String q, Map<Long, String> rawDocTextsByDocId, int K, SearchOptions o) throws IOException {
        SearchShardWithExtrasRequest.Builder b = SearchShardWithExtrasRequest.newBuilder()
                .setShardId(shardId).setQuery(q == null ? "" : q).setK(K)
                .setOptions(ProtoWireConverters.toProto(o == null ? SearchOptions.defaults() : o))
                .setClusterRoot(wireClusterRoot == null ? "" : wireClusterRoot);
        if (rawDocTextsByDocId != null) {
            for (Map.Entry<Long, String> e : rawDocTextsByDocId.entrySet()) {
                b.addRawDocTexts(FulltextShardWire.RawDocPairMsg.newBuilder()
                        .setDocId(e.getKey() == null ? 0L : e.getKey())
                        .setText(e.getValue() == null ? "" : e.getValue())
                        .build());
            }
        }
        SearchShardWithExtrasRequest req = b.build();
        FulltextShardWire.SearchShardWithExtrasResponse r;
        if (inprocMode) {
            r = callInproc(P2PCommand.FULLTEXT_SHARD_SEARCH_EXTRAS,
                    P2PCommand.R_OK_FULLTEXT_SHARD_SEARCH_EXTRAS_ACK, req, FulltextShardWire.SearchShardWithExtrasResponse.class);
        } else {
            r = callTcp(shardId, P2PCommand.FULLTEXT_SHARD_SEARCH_EXTRAS, P2PCommand.R_OK_FULLTEXT_SHARD_SEARCH_EXTRAS_ACK,
                    req, bytes -> { try { return FulltextShardWire.SearchShardWithExtrasResponse.parseFrom(bytes); } catch (Exception e) { throw new RuntimeException(e); } },
                    FulltextShardWire.SearchShardWithExtrasResponse::getStatusCode,
                    FulltextShardWire.SearchShardWithExtrasResponse::getErrorMessage);
        }
        if (r.getStatusCode() != 0) throw new IOException("searchWithExtras status=" + r.getStatusCode() + ":" + r.getErrorMessage());
        List<ScoredDocHitWithExtras> out = new ArrayList<>(r.getHitsCount());
        for (int i = 0; i < r.getHitsCount(); i++) out.add(ProtoWireConverters.fromProto(r.getHits(i)));
        return out;
    }

    @Override public IngestStat putDocBatch(int shardId, Map<Long, String> docId2text) throws IOException {
        if (docId2text == null || docId2text.isEmpty()) return IngestStat.zeroForTest();
        IngestStat acc = IngestStat.zeroForTest();
        for (Map.Entry<Long, String> e : docId2text.entrySet()) {
            IngestStat one = putDoc(shardId, e.getKey(), e.getValue());
            acc = acc.mergeWith(one);
        }
        return acc;
    }
}
