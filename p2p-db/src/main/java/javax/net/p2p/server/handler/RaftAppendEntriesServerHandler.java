package javax.net.p2p.server.handler;

import com.q3lives.ds.registry.meta.raft.RaftServer;
import com.q3lives.ds.registry.meta.raft.RaftServer.AppendResult;
import com.q3lives.ds.registry.meta.raft.RaftServer.LogEntry;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class RaftAppendEntriesServerHandler implements P2PCommandHandler {

    public static final class AppendEntriesReq implements Serializable {
        public final long term;
        public final long leaderId;
        public final long prevLogIndex;
        public final long prevLogTerm;
        public final long leaderCommit;
        public final List<byte[]> entries;
        public final List<Byte> entryTypes;
        public final List<Long> entryTerms;
        public final List<String> entryPaths;

        public AppendEntriesReq(long term, long leaderId, long prevLogIndex, long prevLogTerm,
                                long leaderCommit, List<LogEntry> entries) {
            this.term = term;
            this.leaderId = leaderId;
            this.prevLogIndex = prevLogIndex;
            this.prevLogTerm = prevLogTerm;
            this.leaderCommit = leaderCommit;
            List<byte[]> ent = new ArrayList<>();
            List<Byte> tps = new ArrayList<>();
            List<Long> tms = new ArrayList<>();
            List<String> pths = new ArrayList<>();
            if (entries != null) {
                for (LogEntry e : entries) {
                    ent.add(e.payload);
                    tps.add(e.cmdType);
                    tms.add(e.term);
                    pths.add(e.registryPath);
                }
            }
            this.entries = ent;
            this.entryTypes = tps;
            this.entryTerms = tms;
            this.entryPaths = pths;
        }

        public List<LogEntry> toLogEntries() {
            List<LogEntry> res = new ArrayList<>(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                res.add(new LogEntry(entryTerms.get(i), entryTypes.get(i), entryPaths.get(i), entries.get(i)));
            }
            return res;
        }

        public static AppendEntriesReq fromBytes(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            long term = bb.getLong();
            long leaderId = bb.getLong();
            long prevLogIndex = bb.getLong();
            long prevLogTerm = bb.getLong();
            long leaderCommit = bb.getLong();
            int n = bb.getInt();
            List<LogEntry> ent = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long t = bb.getLong();
                byte ty = bb.get();
                int pathLen = bb.getInt();
                byte[] pb = new byte[pathLen];
                bb.get(pb);
                String path = new String(pb);
                int pl = bb.getInt();
                byte[] p = new byte[pl];
                bb.get(p);
                ent.add(new LogEntry(t, ty, path, p));
            }
            return new AppendEntriesReq(term, leaderId, prevLogIndex, prevLogTerm, leaderCommit, ent);
        }

        public byte[] toBytes() {
            int total = 5 * 8 + 4;
            byte[][] pathBs = new byte[entries.size()][];
            for (int i = 0; i < entries.size(); i++) {
                pathBs[i] = entryPaths.get(i).getBytes();
                total += 8 + 1 + 4 + pathBs[i].length + 4 + entries.get(i).length;
            }
            ByteBuffer bb = ByteBuffer.allocate(total);
            bb.putLong(term).putLong(leaderId).putLong(prevLogIndex).putLong(prevLogTerm).putLong(leaderCommit);
            bb.putInt(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                bb.putLong(entryTerms.get(i)).put(entryTypes.get(i));
                bb.putInt(pathBs[i].length).put(pathBs[i]);
                bb.putInt(entries.get(i).length).put(entries.get(i));
            }
            return bb.array();
        }
    }

    public static final class AppendEntriesResp implements Serializable {
        public final long term;
        public final boolean success;
        public final long matchIndex;

        public AppendEntriesResp(long term, boolean success, long matchIndex) {
            this.term = term;
            this.success = success;
            this.matchIndex = matchIndex;
        }

        public static AppendEntriesResp fromBytes(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            return new AppendEntriesResp(bb.getLong(), bb.get() != 0, bb.getLong());
        }

        public byte[] toBytes() {
            ByteBuffer bb = ByteBuffer.allocate(17);
            bb.putLong(term).put((byte) (success ? 1 : 0)).putLong(matchIndex);
            return bb.array();
        }
    }

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.RAFT_APPEND_ENTRIES;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.RAFT_APPEND_ENTRIES) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            byte[] data;
            if (request.getData() instanceof byte[]) {
                data = (byte[]) request.getData();
            } else {
                try {
                    java.lang.reflect.Method toBytes = request.getData().getClass().getMethod("toBytes");
                    Object o = toBytes.invoke(request.getData());
                    data = (byte[]) o;
                } catch (Exception e) {
                    return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "payload cannot convert to bytes");
                }
            }
            AppendEntriesReq req = AppendEntriesReq.fromBytes(data);
            RaftServer server = null;
            for (RaftServer s : RaftServer.PEERS.values()) {
                server = s;
                break;
            }
            if (server == null) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_RAFT_APPEND_ENTRIES_ACK,
                    new AppendEntriesResp(0L, false, 0L).toBytes());
            }
            AppendResult r = server.appendEntries(req.leaderId, req.term, req.prevLogIndex, req.prevLogTerm,
                req.toLogEntries(), req.leaderCommit);
            AppendEntriesResp resp = new AppendEntriesResp(r.term, r.success, r.matchIndex);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_RAFT_APPEND_ENTRIES_ACK, resp.toBytes());
        } catch (Throwable t) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, t.getMessage() == null ? "append error" : t.getMessage());
        }
    }
}
