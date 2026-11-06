package javax.net.p2p.server.handler;

import com.q3lives.ds.registry.meta.raft.RaftServer;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

import java.io.Serializable;
import java.nio.ByteBuffer;

public class RaftRequestVoteServerHandler implements P2PCommandHandler {

    public static final class RequestVoteReq implements Serializable {
        public final long term;
        public final long candidateId;
        public final long lastLogIndex;
        public final long lastLogTerm;

        public RequestVoteReq(long term, long candidateId, long lastLogIndex, long lastLogTerm) {
            this.term = term;
            this.candidateId = candidateId;
            this.lastLogIndex = lastLogIndex;
            this.lastLogTerm = lastLogTerm;
        }

        public static RequestVoteReq fromBytes(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            return new RequestVoteReq(bb.getLong(), bb.getLong(), bb.getLong(), bb.getLong());
        }

        public byte[] toBytes() {
            ByteBuffer bb = ByteBuffer.allocate(32);
            bb.putLong(term).putLong(candidateId).putLong(lastLogIndex).putLong(lastLogTerm);
            return bb.array();
        }
    }

    public static final class RequestVoteResp implements Serializable {
        public final long term;
        public final boolean voteGranted;

        public RequestVoteResp(long term, boolean voteGranted) {
            this.term = term;
            this.voteGranted = voteGranted;
        }

        public static RequestVoteResp fromBytes(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            return new RequestVoteResp(bb.getLong(), bb.get() != 0);
        }

        public byte[] toBytes() {
            ByteBuffer bb = ByteBuffer.allocate(9);
            bb.putLong(term).put((byte)(voteGranted ? 1 : 0));
            return bb.array();
        }
    }

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.RAFT_REQUEST_VOTE;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.RAFT_REQUEST_VOTE) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            byte[] data;
            if (request.getData() instanceof byte[]) data = (byte[]) request.getData();
            else if (request.getData() instanceof Serializable) data = ((Serializable) request.getData()).toString().getBytes();
            else {
                try {
                    java.lang.reflect.Method toBytes = request.getData().getClass().getMethod("toBytes");
                    Object o = toBytes.invoke(request.getData());
                    data = (byte[]) o;
                } catch (Exception e) {
                    return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "payload cannot convert to bytes");
                }
            }
            RequestVoteReq req = RequestVoteReq.fromBytes(data);
            RaftServer server = null;
            for (RaftServer s : RaftServer.PEERS.values()) {
                server = s;
                break;
            }
            if (server == null) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_RAFT_REQUEST_VOTE_ACK,
                    new RequestVoteResp(0L, false).toBytes());
            }
            RaftServer.VoteResult vr = server.requestVote(req.term, req.candidateId, req.lastLogIndex, req.lastLogTerm);
            RequestVoteResp resp = new RequestVoteResp(vr.term, vr.voteGranted);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_RAFT_REQUEST_VOTE_ACK, resp.toBytes());
        } catch (Throwable t) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, t.getMessage() == null ? "reqvote error" : t.getMessage());
        }
    }
}
