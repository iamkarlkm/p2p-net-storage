package javax.net.p2p.rpc.server;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.rpc.pubsub.proto.PubSubEvent;
import javax.net.p2p.rpc.proto.RpcFrame;

/**
 * RPC 事件订阅 broker。
 */
public final class RpcPubSubBroker {
    private static final ConcurrentMap<String, ConcurrentMap<Long, Subscriber>> TOPIC_SUBS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Integer, AtomicInteger> EXECUTOR_SUB_COUNT = new ConcurrentHashMap<>();

    private RpcPubSubBroker() {
    }

    public static boolean subscribe(String topic, int seq, AbstractSendMesageExecutor executor, RpcFrame requestFrame) {
        if (topic == null || topic.isBlank() || executor == null || requestFrame == null) {
            return false;
        }
        if (!isTopicAllowed(topic)) {
            return false;
        }
        ConcurrentMap<Long, Subscriber> subs = TOPIC_SUBS.computeIfAbsent(topic, ignored -> new ConcurrentHashMap<>());
        if (subs.size() >= maxSubscribersPerTopic()) {
            return false;
        }
        int executorId = System.identityHashCode(executor);
        AtomicInteger count = EXECUTOR_SUB_COUNT.computeIfAbsent(executorId, ignored -> new AtomicInteger(0));
        int now = count.incrementAndGet();
        if (now > maxSubscriptionsPerExecutor()) {
            count.decrementAndGet();
            cleanupExecutorCount(executorId, count);
            return false;
        }
        long key = subscriberKey(executorId, seq);
        Subscriber previous = subs.putIfAbsent(key, new Subscriber(topic, seq, executor, executorId, requestFrame));
        if (previous != null) {
            count.decrementAndGet();
            cleanupExecutorCount(executorId, count);
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
        int executorId = System.identityHashCode(executor);
        Subscriber removed = subs.remove(subscriberKey(executorId, seq));
        if (removed != null) {
            removed.cleanup();
            decrementExecutorCount(executorId);
        }
        if (subs.isEmpty()) {
            TOPIC_SUBS.remove(topic, subs);
        }
    }

    public static int publish(String topic, String message) {
        if (!isTopicAllowed(topic)) {
            return 0;
        }
        ConcurrentMap<Long, Subscriber> subs = TOPIC_SUBS.get(topic);
        if (subs == null || subs.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (Subscriber subscriber : subs.values()) {
            if (!subscriber.executor.isActive()) {
                subs.remove(subscriberKey(subscriber.executorId, subscriber.seq));
                subscriber.cleanup();
                decrementExecutorCount(subscriber.executorId);
                continue;
            }
            int index = subscriber.index.getAndIncrement();
            try {
                boolean accepted = subscriber.publish(topic, message, index);
                if (accepted) {
                    delivered++;
                } else {
                    subs.remove(subscriberKey(subscriber.executorId, subscriber.seq));
                    decrementExecutorCount(subscriber.executorId);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                subs.remove(subscriberKey(subscriber.executorId, subscriber.seq));
                subscriber.cleanup();
                decrementExecutorCount(subscriber.executorId);
            }
        }
        if (subs.isEmpty()) {
            TOPIC_SUBS.remove(topic, subs);
        }
        return delivered;
    }

    public static int subscriberCount(String topic) {
        ConcurrentMap<Long, Subscriber> subs = TOPIC_SUBS.get(topic);
        return subs == null ? 0 : subs.size();
    }

    public static boolean isTopicAllowed(String topic) {
        if (topic == null) {
            return false;
        }
        String trimmed = topic.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            return false;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char ch = trimmed.charAt(index);
            boolean allowed = (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '.'
                || ch == '_'
                || ch == '-';
            if (!allowed) {
                return false;
            }
        }
        String allow = P2PConfig.getOptionalProperty("p2p.pubsub.topic.allowlist");
        if (allow == null || allow.isBlank()) {
            return true;
        }
        return splitToSet(allow).contains(trimmed);
    }

    private static int maxSubscribersPerTopic() {
        return readInt("p2p.pubsub.maxSubscribersPerTopic", 1024);
    }

    private static int maxSubscriptionsPerExecutor() {
        return readInt("p2p.pubsub.maxSubscriptionsPerExecutor", 128);
    }

    private static int readInt(String key, int defaultValue) {
        String value = P2PConfig.getOptionalProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static Set<String> splitToSet(String allow) {
        Set<String> output = new HashSet<>();
        for (String item : allow.split(",")) {
            if (item == null) {
                continue;
            }
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                output.add(trimmed);
            }
        }
        return output;
    }

    private static long subscriberKey(int executorId, int seq) {
        return ((long) executorId << 32) | (seq & 0xffffffffL);
    }

    private static void decrementExecutorCount(int executorId) {
        AtomicInteger count = EXECUTOR_SUB_COUNT.get(executorId);
        if (count == null) {
            return;
        }
        count.decrementAndGet();
        cleanupExecutorCount(executorId, count);
    }

    private static void cleanupExecutorCount(int executorId, AtomicInteger count) {
        if (count.get() <= 0) {
            EXECUTOR_SUB_COUNT.remove(executorId, count);
        }
    }

    private static final class Subscriber {
        final String topic;
        final int seq;
        final AbstractSendMesageExecutor executor;
        final int executorId;
        final RpcFrame requestFrame;
        final AtomicInteger index = new AtomicInteger(1);
        final RpcQueuedFrameSender frameSender;

        private Subscriber(String topic, int seq, AbstractSendMesageExecutor executor, int executorId, RpcFrame requestFrame) {
            this.topic = topic;
            this.seq = seq;
            this.executor = executor;
            this.executorId = executorId;
            this.requestFrame = requestFrame;
            this.frameSender = new RpcQueuedFrameSender(executor, seq, P2PCommand.RPC_EVENT, requestFrame, null);
        }

        private boolean publish(String topic, String message, int index) throws InterruptedException {
            try {
                byte[] payload = PubSubEvent.newBuilder()
                    .setTopic(topic)
                    .setMessage(message == null ? "" : message)
                    .setIndex(index)
                    .build()
                    .toByteArray();
                frameSender.sendChunkedPayload(requestFrame, payload);
                return true;
            } catch (InterruptedException ex) {
                throw ex;
            } catch (Exception ex) {
                return false;
            }
        }

        private void cleanup() {
            frameSender.close();
        }
    }
}
