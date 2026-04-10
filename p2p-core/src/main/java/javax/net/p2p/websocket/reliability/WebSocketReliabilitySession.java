package javax.net.p2p.websocket.reliability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import javax.net.p2p.api.P2PCommand;

final class WebSocketReliabilitySession {

    final String sessionId;

    int nextSendSeq = 1;
    int expectedSeq = 1;
    int lastDelivered = 0;
    int lastAckSent = 0;
    long lastAckSentAt = 0;

    final NavigableMap<Integer, Pending> pending = new TreeMap<>();
    final NavigableMap<Integer, byte[]> reorderBuffer = new TreeMap<>();

    final LruMap<BusinessKey, byte[]> responseCache = new LruMap<>(4096);
    final LruMap<BusinessKey, Boolean> seenBusiness = new LruMap<>(8192);
    final LruMap<Integer, P2PCommand> requestCmdBySeq = new LruMap<>(8192);

    WebSocketReliabilitySession(String sessionId) {
        this.sessionId = sessionId;
    }

    static final class Pending {
        final javax.net.p2p.model.P2PWrapper frame;
        long lastSentAt;
        int retries;
        boolean sentOnce;

        Pending(javax.net.p2p.model.P2PWrapper frame) {
            this.frame = frame;
        }
    }

    static final class BusinessKey {
        final int seq;
        final int command;

        BusinessKey(int seq, int command) {
            this.seq = seq;
            this.command = command;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BusinessKey that = (BusinessKey) o;
            return seq == that.seq && command == that.command;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(seq);
            result = 31 * result + Integer.hashCode(command);
            return result;
        }
    }

    static final class LruMap<K, V> extends LinkedHashMap<K, V> {
        private final int max;

        LruMap(int max) {
            super(16, 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > max;
        }
    }
}

