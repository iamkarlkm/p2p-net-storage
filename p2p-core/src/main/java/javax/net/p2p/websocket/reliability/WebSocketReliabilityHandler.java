package javax.net.p2p.websocket.reliability;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;

public final class WebSocketReliabilityHandler extends ChannelDuplexHandler {

    public static final AttributeKey<String> SESSION_ID = AttributeKey.valueOf("p2p.ws.sessionId");

    private static final int DEFAULT_MAX_RETRIES = 20;
    private static final long DEFAULT_ACK_TIMEOUT_MS = 200;
    private static final long DEFAULT_TICK_MS = 50;
    private static final long DEFAULT_ACK_INTERVAL_MS = 20;
    private static final int DEFAULT_MAX_BUFFER = 2048;

    private static final ConcurrentHashMap<String, WebSocketReliabilitySession> SESSIONS = new ConcurrentHashMap<>();

    private final boolean clientSide;
    private final String fixedSessionId;
    private final int maxRetries;
    private final long ackTimeoutMs;
    private final long tickMs;
    private final long ackIntervalMs;
    private final int maxBuffer;
    private final double dropRate;
    private final Random random;

    private WebSocketReliabilitySession session;
    private ScheduledFuture<?> ticker;

    public WebSocketReliabilityHandler() {
        this(false, null);
    }

    public WebSocketReliabilityHandler(String sessionId) {
        this(true, sessionId);
    }

    private WebSocketReliabilityHandler(boolean clientSide, String sessionId) {
        int maxRetries = Integer.getInteger("p2p.ws.reliability.maxRetries", DEFAULT_MAX_RETRIES);
        long ackTimeoutMs = Long.getLong("p2p.ws.reliability.ackTimeoutMs", DEFAULT_ACK_TIMEOUT_MS);
        long tickMs = Long.getLong("p2p.ws.reliability.tickMs", DEFAULT_TICK_MS);
        long ackIntervalMs = Long.getLong("p2p.ws.reliability.ackIntervalMs", DEFAULT_ACK_INTERVAL_MS);
        int maxBuffer = Integer.getInteger("p2p.ws.reliability.maxBuffer", DEFAULT_MAX_BUFFER);
        double dropRate = Double.parseDouble(System.getProperty("p2p.ws.reliability.dropRate", "0"));
        long seed = Long.getLong("p2p.ws.reliability.randomSeed", 1L);

        this.clientSide = clientSide;
        this.fixedSessionId = sessionId;
        this.maxRetries = Math.max(1, maxRetries);
        this.ackTimeoutMs = Math.max(10, ackTimeoutMs);
        this.tickMs = Math.max(10, tickMs);
        this.ackIntervalMs = Math.max(0, ackIntervalMs);
        this.maxBuffer = Math.max(16, maxBuffer);
        this.dropRate = Math.max(0.0d, Math.min(1.0d, dropRate));
        this.random = new Random(seed);
    }

    public static int getLastDelivered(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        WebSocketReliabilitySession s = SESSIONS.get(sessionId);
        return s == null ? 0 : s.lastDelivered;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        if (ticker != null) {
            return;
        }
        ticker = ctx.executor().scheduleAtFixedRate(() -> tick(ctx), tickMs, tickMs, TimeUnit.MILLISECONDS);
        if (clientSide && fixedSessionId != null && !fixedSessionId.isBlank()) {
            attachSession(ctx, fixedSessionId);
        } else {
            String sid = ctx.channel().attr(SESSION_ID).get();
            if (sid != null && !sid.isBlank()) {
                attachSession(ctx, sid);
            }
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }
        session = null;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        handlerRemoved(ctx);
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof P2PWrapper)) {
            super.channelRead(ctx, msg);
            return;
        }
        P2PWrapper wrapper = (P2PWrapper) msg;
        P2PCommand cmd = wrapper.getCommand();
        if (cmd == P2PCommand.WS_SESSION_HELLO) {
            handleSessionHello(ctx, wrapper);
            return;
        }
        if (cmd == P2PCommand.WS_SESSION_STATE) {
            ensureSession(ctx);
            WebSocketReliabilitySession s = session;
            if (s != null) {
                handleAck(wrapper.getSeq(), s);
            }
            return;
        }
        ensureSession(ctx);
        WebSocketReliabilitySession s = session;
        if (s == null) {
            super.channelRead(ctx, msg);
            return;
        }
        if (cmd == P2PCommand.WS_FRAME_ACK) {
            handleAck(wrapper.getSeq(), s);
            return;
        }
        if (cmd == P2PCommand.WS_FRAME_RESET) {
            handleReset(ctx, wrapper.getSeq(), s);
            return;
        }
        if (cmd == P2PCommand.WS_FRAME) {
            handleFrame(ctx, wrapper, s);
            return;
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof P2PWrapper)) {
            super.write(ctx, msg, promise);
            return;
        }
        P2PWrapper wrapper = (P2PWrapper) msg;
        P2PCommand cmd = wrapper.getCommand();
        if (cmd == P2PCommand.WS_FRAME
            || cmd == P2PCommand.WS_FRAME_ACK
            || cmd == P2PCommand.WS_FRAME_RESET
            || cmd == P2PCommand.WS_SESSION_HELLO
            || cmd == P2PCommand.WS_SESSION_STATE) {
            super.write(ctx, msg, promise);
            return;
        }
        ensureSession(ctx);
        WebSocketReliabilitySession s = session;
        if (s == null) {
            super.write(ctx, msg, promise);
            return;
        }
        cacheResponseIfAny(wrapper, s);

        byte[] payload = SerializationUtil.serialize(wrapper);
        int seq = s.nextSendSeq++;
        P2PWrapper<byte[]> frame = P2PWrapper.build(seq, P2PCommand.WS_FRAME, payload);
        WebSocketReliabilitySession.Pending p = new WebSocketReliabilitySession.Pending(frame);
        s.pending.put(seq, p);
        if (dropRate > 0.0d && !p.sentOnce && random.nextDouble() < dropRate) {
            p.sentOnce = true;
            p.lastSentAt = System.currentTimeMillis();
            promise.trySuccess();
            return;
        }
        p.sentOnce = true;
        p.lastSentAt = System.currentTimeMillis();
        super.write(ctx, frame, promise);
    }

    private void handleAck(int ackSeq, WebSocketReliabilitySession s) {
        if (s.pending.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, WebSocketReliabilitySession.Pending>> it = s.pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, WebSocketReliabilitySession.Pending> e = it.next();
            if (e.getKey() <= ackSeq) {
                it.remove();
            } else {
                break;
            }
        }
    }

    private void handleReset(ChannelHandlerContext ctx, int resetSeq, WebSocketReliabilitySession s) {
        handleAck(resetSeq, s);
        for (WebSocketReliabilitySession.Pending p : s.pending.values()) {
            p.lastSentAt = 0;
        }
        tick(ctx, s);
    }

    private void handleFrame(ChannelHandlerContext ctx, P2PWrapper wrapper, WebSocketReliabilitySession s) {
        int seq = wrapper.getSeq();
        Object data = wrapper.getData();
        if (!(data instanceof byte[])) {
            sendAck(ctx, s.lastDelivered, s);
            return;
        }
        byte[] payload = (byte[]) data;

        if (seq < s.expectedSeq) {
            sendAck(ctx, s.lastDelivered, s);
            return;
        }
        if (seq == s.expectedSeq) {
            deliver(ctx, payload, s);
            s.lastDelivered = seq;
            s.expectedSeq++;
            drain(ctx, s);
            sendAck(ctx, s.lastDelivered, s);
            return;
        }

        if (s.reorderBuffer.size() >= maxBuffer) {
            s.reorderBuffer.clear();
            sendReset(ctx, s.lastDelivered);
            sendAck(ctx, s.lastDelivered, s);
            return;
        }
        s.reorderBuffer.put(seq, payload);
        sendAck(ctx, s.lastDelivered, s);
    }

    private void drain(ChannelHandlerContext ctx, WebSocketReliabilitySession s) {
        while (true) {
            byte[] next = s.reorderBuffer.remove(s.expectedSeq);
            if (next == null) {
                break;
            }
            deliver(ctx, next, s);
            s.lastDelivered = s.expectedSeq;
            s.expectedSeq++;
        }
    }

    private void deliver(ChannelHandlerContext ctx, byte[] payload, WebSocketReliabilitySession s) {
        try {
            P2PWrapper inner = SerializationUtil.deserialize(P2PWrapper.class, payload);
            if (inner == null || inner.getCommand() == null) {
                return;
            }
            WebSocketReliabilitySession.BusinessKey key = new WebSocketReliabilitySession.BusinessKey(inner.getSeq(), inner.getCommand().getValue());
            if (s.seenBusiness.get(key) != null) {
                byte[] cached = s.responseCache.get(key);
                if (cached != null) {
                    P2PWrapper resp = SerializationUtil.deserialize(P2PWrapper.class, cached);
                    if (resp != null) {
                        ctx.writeAndFlush(resp);
                    }
                }
                return;
            }
            s.seenBusiness.put(key, Boolean.TRUE);
            s.requestCmdBySeq.put(inner.getSeq(), inner.getCommand());
            ctx.fireChannelRead(inner);
        } catch (Exception e) {
            ctx.fireExceptionCaught(e);
        }
    }

    private void sendAck(ChannelHandlerContext ctx, int ackSeq, WebSocketReliabilitySession s) {
        long now = System.currentTimeMillis();
        if (ackSeq <= s.lastAckSent && (ackIntervalMs <= 0 || now - s.lastAckSentAt < ackIntervalMs)) {
            return;
        }
        s.lastAckSent = ackSeq;
        s.lastAckSentAt = now;
        ctx.writeAndFlush(P2PWrapper.build(ackSeq, P2PCommand.WS_FRAME_ACK, null));
    }

    private void sendReset(ChannelHandlerContext ctx, int resetSeq) {
        ctx.writeAndFlush(P2PWrapper.build(resetSeq, P2PCommand.WS_FRAME_RESET, null));
    }

    private void tick(ChannelHandlerContext ctx) {
        ensureSession(ctx);
        WebSocketReliabilitySession s = session;
        if (s == null) {
            return;
        }
        tick(ctx, s);
    }

    private void tick(ChannelHandlerContext ctx, WebSocketReliabilitySession s) {
        if (s.pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, WebSocketReliabilitySession.Pending>> it = s.pending.entrySet().iterator();
        while (it.hasNext()) {
            WebSocketReliabilitySession.Pending p = it.next().getValue();
            if (p.lastSentAt == 0 || now - p.lastSentAt >= ackTimeoutMs) {
                if (p.retries >= maxRetries) {
                    it.remove();
                    ctx.close();
                    return;
                }
                p.retries++;
                p.lastSentAt = now;
                ctx.writeAndFlush(p.frame);
            }
        }
    }

    private void ensureSession(ChannelHandlerContext ctx) {
        if (session != null) {
            return;
        }
        String sid = fixedSessionId;
        if (sid == null || sid.isBlank()) {
            sid = ctx.channel().attr(SESSION_ID).get();
        }
        if (sid != null && !sid.isBlank()) {
            attachSession(ctx, sid);
        }
    }

    private void attachSession(ChannelHandlerContext ctx, String sid) {
        WebSocketReliabilitySession s = SESSIONS.computeIfAbsent(sid, WebSocketReliabilitySession::new);
        ctx.channel().attr(SESSION_ID).set(sid);
        this.session = s;
        if (!s.pending.isEmpty()) {
            ctx.executor().execute(() -> tick(ctx, s));
        }
    }

    private void handleSessionHello(ChannelHandlerContext ctx, P2PWrapper wrapper) {
        String raw = null;
        Object d = wrapper.getData();
        if (d instanceof String) {
            raw = (String) d;
        } else if (d instanceof byte[]) {
            raw = new String((byte[]) d, StandardCharsets.UTF_8);
        }
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] parts = raw.split("\\|", 2);
        String sid = parts[0];
        int ack = 0;
        if (parts.length > 1) {
            try {
                ack = Integer.parseInt(parts[1]);
            } catch (Exception e) {
                ack = 0;
            }
        }
        if (sid == null || sid.isBlank()) {
            return;
        }
        attachSession(ctx, sid);
        WebSocketReliabilitySession s = session;
        if (s == null) {
            return;
        }
        if (ack > 0) {
            handleAck(ack, s);
        }
        ctx.writeAndFlush(P2PWrapper.build(s.lastDelivered, P2PCommand.WS_SESSION_STATE, null));
    }

    private void cacheResponseIfAny(P2PWrapper response, WebSocketReliabilitySession s) {
        if (response == null || response.getCommand() == null) {
            return;
        }
        P2PCommand reqCmd = s.requestCmdBySeq.get(response.getSeq());
        if (reqCmd == null) {
            return;
        }
        WebSocketReliabilitySession.BusinessKey k = new WebSocketReliabilitySession.BusinessKey(response.getSeq(), reqCmd.getValue());
        byte[] payload = SerializationUtil.serialize(response);
        s.responseCache.put(k, payload);
    }
}
