package javax.net.p2p.server.handler;

import com.q3lives.ds.registry.meta.raft.RaftServer;
import com.q3lives.ds.registry.meta.raft.RaftServer.SnapshotResult;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

import java.io.Serializable;
import java.nio.ByteBuffer;

public class RaftInstallSnapshotServerHandler implements P2PCommandHandler {

    public static final class InstallSnapshotReq implements Serializable {
        public final long term;
        public final long leaderId;
        public final long lastIncludedIndex;
        public final long lastIncludedTerm;
        public final long offset;
        public final byte[] data;
        public final boolean done;

        public InstallSnapshotReq(long term, long leaderId, long lastIncludedIndex, long lastIncludedTerm,
                                  long offset, byte[] data, boolean done) {
            this.term = term;
            this.leaderId = leaderId;
            this.lastIncludedIndex = lastIncludedIndex;
            this.lastIncludedTerm = lastIncludedTerm;
            this.offset = offset;
            this.data = data == null ? new byte[0] : data;
            this.done = done;
        }

        public static InstallSnapshotReq fromBytes(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            long t = bb.getLong(), lid = bb.getLong(), li = bb.getLong(), lt = bb.getLong(), off = bb.getLong();
            int len = bb.getInt();
            byte[] d = new byte[len];
            bb.get(d);
            boolean done = bb.get() != 0;
            return new InstallSnapshotReq(t, lid, li, lt, off, d, done);
        }

        public byte[] toBytes() {
            ByteBuffer bb = ByteBuffer.allocate(5 * 8 + 4 + data.length + 1);
            bb.putLong(term).putLong(leaderId).putLong(lastIncludedIndex).putLong(lastIncludedTerm).putLong(offset);
            bb.putInt(data.length).put(data).put((byte) (done ? 1 : 0));
            return bb.array();
        }
    }

    public static final class InstallSnapshotResp implements Serializable {
        public final long term;
        public final boolean success;

        public InstallSnapshotResp(long term, boolean success) {
            this.term = term;
            this.success = success;
        }

        public static InstallSnapshotResp fromBytes(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            return new InstallSnapshotResp(bb.getLong(), bb.get() != 0);
        }

        public byte[] toBytes() {
            ByteBuffer bb = ByteBuffer.allocate(9);
            bb.putLong(term).put((byte) (success ? 1 : 0));
            return bb.array();
        }
    }

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.RAFT_INSTALL_SNAPSHOT;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.RAFT_INSTALL_SNAPSHOT) {
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
            InstallSnapshotReq req = InstallSnapshotReq.fromBytes(data);
            RaftServer server = null;
            for (RaftServer s : RaftServer.PEERS.values()) {
                server = s;
                break;
            }
            if (server == null) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_RAFT_INSTALL_SNAPSHOT_ACK,
                    new InstallSnapshotResp(0L, false).toBytes());
            }
            SnapshotResult r = server.installSnapshot(new RaftServer.InstallSnapshotChunk(
                req.term, req.leaderId, req.lastIncludedIndex, req.lastIncludedTerm, req.offset, req.data, req.done));
            InstallSnapshotResp resp = new InstallSnapshotResp(r.term, r.success);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_RAFT_INSTALL_SNAPSHOT_ACK, resp.toBytes());
        } catch (Throwable t) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, t.getMessage() == null ? "snapshot error" : t.getMessage());
        }
    }
}
