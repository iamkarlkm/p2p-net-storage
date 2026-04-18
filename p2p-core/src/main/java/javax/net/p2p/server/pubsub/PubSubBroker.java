package javax.net.p2p.server.pubsub;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.model.P2PPubSubMessage;
import javax.net.p2p.model.StreamP2PWrapper;

public final class PubSubBroker {
    private static final ConcurrentMap<String, ConcurrentMap<Long, Subscriber>> TOPIC_SUBS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Integer, AtomicInteger> EXECUTOR_SUB_COUNT = new ConcurrentHashMap<>();

    private PubSubBroker() {
    }

    public static boolean subscribe(String topic, int seq, AbstractSendMesageExecutor executor) {
        if (topic == null || topic.isBlank() || executor == null) {
            return false;
        }
        if (!isTopicAllowed(topic)) {
            return false;
        }
        ConcurrentMap<Long, Subscriber> subs = TOPIC_SUBS.computeIfAbsent(topic, k -> new ConcurrentHashMap<>());
        if (subs.size() >= maxSubscribersPerTopic()) {
            return false;
        }
        int execId = System.identityHashCode(executor);
        AtomicInteger c = EXECUTOR_SUB_COUNT.computeIfAbsent(execId, k -> new AtomicInteger(0));
        int now = c.incrementAndGet();
        if (now > maxSubscriptionsPerExecutor()) {
            c.decrementAndGet();
            cleanupExecutorCount(execId, c);
            return false;
        }
        long key = subscriberKey(execId, seq);
        Subscriber prev = subs.putIfAbsent(key, new Subscriber(seq, executor, execId));
        if (prev != null) {
            c.decrementAndGet();
            cleanupExecutorCount(execId, c);
            return false;
        }
        return true;
    }

    public static void unsubscribe(String topic, int seq, AbstractSendMesageExecutor executor) {
        if (topic == null || topic.isBlank() || executor == null) {
            return;
        }
        ConcurrentMap<Long, Subscriber> subs = TOPIC_SUBS.get(topic);
        if (subs == null) {
            return;
        }
        int execId = System.identityHashCode(executor);
        Subscriber removed = subs.remove(subscriberKey(execId, seq));
        if (removed != null) {
            decrementExecutorCount(execId);
        }
        if (subs.isEmpty()) {
            TOPIC_SUBS.remove(topic, subs);
        }
    }

    public static void publish(P2PPubSubMessage msg) {
        if (msg == null || msg.topic == null || msg.topic.isBlank()) {
            return;
        }
        if (!isTopicAllowed(msg.topic)) {
            return;
        }
        ConcurrentMap<Long, Subscriber> subs = TOPIC_SUBS.get(msg.topic);
        if (subs == null || subs.isEmpty()) {
            return;
        }
        for (Subscriber s : subs.values()) {
            if (!s.executor.isActive()) {
                subs.remove(subscriberKey(s.executorId, s.seq));
                decrementExecutorCount(s.executorId);
                continue;
            }
            int index = s.index.getAndIncrement();
            try {
                s.executor.sendResponse(StreamP2PWrapper.buildStream(s.seq, index, P2PCommand.PUBSUB_STREAM, msg, false));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                subs.remove(subscriberKey(s.executorId, s.seq));
                decrementExecutorCount(s.executorId);
            }
        }
        if (subs.isEmpty()) {
            TOPIC_SUBS.remove(msg.topic, subs);
        }
    }

    public static int subscriberCount(String topic) {
        ConcurrentMap<Long, Subscriber> subs = TOPIC_SUBS.get(topic);
        return subs == null ? 0 : subs.size();
    }

    public static boolean isTopicAllowed(String topic) {
        if (topic == null) {
            return false;
        }
        String t = topic.trim();
        if (t.isEmpty() || t.length() > 64) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '.'
                || ch == '_'
                || ch == '-';
            if (!ok) {
                return false;
            }
        }
        String allow = P2PConfig.getOptionalProperty("p2p.pubsub.topic.allowlist");
        if (allow == null || allow.isBlank()) {
            return true;
        }
        Set<String> s = splitToSet(allow);
        return s.contains(t);
    }

    private static int maxSubscribersPerTopic() {
        return readInt("p2p.pubsub.maxSubscribersPerTopic", 1024);
    }

    private static int maxSubscriptionsPerExecutor() {
        return readInt("p2p.pubsub.maxSubscriptionsPerExecutor", 128);
    }

    private static int readInt(String key, int def) {
        String v = P2PConfig.getOptionalProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static Set<String> splitToSet(String allow) {
        String[] parts = allow.split(",");
        Set<String> out = new HashSet<>();
        for (String p : parts) {
            if (p == null) continue;
            String s = p.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static long subscriberKey(int executorId, int seq) {
        return ((long) executorId << 32) | (seq & 0xffffffffL);
    }

    private static void decrementExecutorCount(int executorId) {
        AtomicInteger c = EXECUTOR_SUB_COUNT.get(executorId);
        if (c == null) {
            return;
        }
        c.decrementAndGet();
        cleanupExecutorCount(executorId, c);
    }

    private static void cleanupExecutorCount(int executorId, AtomicInteger c) {
        if (c.get() <= 0) {
            EXECUTOR_SUB_COUNT.remove(executorId, c);
        }
    }

    private static final class Subscriber {
        final int seq;
        final AbstractSendMesageExecutor executor;
        final int executorId;
        final AtomicInteger index = new AtomicInteger(1);

        Subscriber(int seq, AbstractSendMesageExecutor executor, int executorId) {
            this.seq = seq;
            this.executor = executor;
            this.executorId = executorId;
        }
    }
}
