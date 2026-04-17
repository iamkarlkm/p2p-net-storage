package p2pws.sdk.center;

import com.google.protobuf.ByteString;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import p2pws.P2PControl;
import p2pws.P2PWrapperOuterClass;
import p2pws.sdk.FrameCodec;
import p2pws.sdk.KeyFileProvider;
import p2pws.sdk.P2PWrapperCodec;
import p2pws.sdk.RsaOaep;
import p2pws.sdk.WireHeader;
import p2pws.sdk.WireFrame;
import p2pws.sdk.XorCipher;

public final class CenterServerHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

    private final KeyFileProvider provider;
    private final byte[] keyId32;
    private final long keyLen;
    private final int magic;
    private final int version;
    private final int flagsPlain;
    private final int flagsEncrypted;
    private final int maxFramePayload;
    private final int ttlSeconds;
    private final RegisteredUsers users;
    private final PresenceStore presence;
    private final SecureRandom rnd = new SecureRandom();

    private long offset = -1;
    private boolean encrypted = false;
    private byte[] selfNodeKey32 = null;
    private long selfNodeId64 = 0;

    public CenterServerHandler(KeyFileProvider provider, byte[] keyId32, long keyLen, int magic, int version, int flagsPlain, int flagsEncrypted, int maxFramePayload, int ttlSeconds, RegisteredUsers users, PresenceStore presence) {
        this.provider = provider;
        this.keyId32 = Arrays.copyOf(keyId32, keyId32.length);
        this.keyLen = keyLen;
        this.magic = magic;
        this.version = version;
        this.flagsPlain = flagsPlain;
        this.flagsEncrypted = flagsEncrypted;
        this.maxFramePayload = maxFramePayload;
        this.ttlSeconds = ttlSeconds;
        this.users = users;
        this.presence = presence;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) {
        ByteBuf content = frame.content();
        byte[] ws = new byte[content.readableBytes()];
        content.readBytes(ws);

        WireFrame wf = FrameCodec.decode(ws);
        byte[] payload = wf.cipherPayload();
        byte[] plain = encrypted ? XorCipher.xorWithKeyFile(payload, provider, keyId32, offset) : payload;
        P2PWrapperOuterClass.P2PWrapper wrapper = P2PWrapperCodec.decode(plain);

        int cmd = wrapper.getCommand();
        if (cmd == -10001) {
            handleHand(ctx, wrapper);
            return;
        }
        if (cmd == -11001) {
            handleCenterHello(ctx, wrapper);
            return;
        }
        if (cmd == -11010) {
            handleGetNode(ctx, wrapper);
            return;
        }
        if (cmd == -11012) {
            handleRelayData(ctx, wrapper);
            return;
        }
        if (cmd == -11030) {
            handleConnectHint(ctx, wrapper);
            return;
        }
    }

    private void handleHand(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PControl.Hand hand = P2PControl.Hand.parseFrom(wrapper.getData());
            boolean ok = false;
            for (ByteString kid : hand.getKeyIdsList()) {
                byte[] b = kid.toByteArray();
                if (b.length == 32 && Arrays.equals(b, keyId32)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                ctx.close();
                return;
            }
            int maxPayload = maxFramePayload;
            if (hand.getMaxFramePayload() > 0 && hand.getMaxFramePayload() < maxPayload) {
                maxPayload = hand.getMaxFramePayload();
            }
            if (keyLen <= maxPayload) {
                ctx.close();
                return;
            }
            long maxOffset = keyLen - maxPayload;
            long off = (Integer.toUnsignedLong(rnd.nextInt()) % maxOffset);
            this.offset = off;

            byte[] sessionId = new byte[16];
            rnd.nextBytes(sessionId);
            P2PControl.HandAckPlain ackPlain = P2PControl.HandAckPlain.newBuilder()
                .setSessionId(ByteString.copyFrom(sessionId))
                .setSelectedKeyId(ByteString.copyFrom(keyId32))
                .setOffset((int) off)
                .setMaxFramePayload(maxPayload)
                .setHeaderPolicyId(0)
                .build();

            PublicKey clientPub = java.security.KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(hand.getClientPubkey().toByteArray()));
            byte[] encryptedAck = RsaOaep.encryptSha256(clientPub, ackPlain.toByteArray());

            P2PWrapperOuterClass.P2PWrapper resp = P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(-10002)
                .setData(ByteString.copyFrom(encryptedAck))
                .build();

            writePlain(ctx, resp);
            this.encrypted = true;
        } catch (Exception e) {
            ctx.close();
        }
    }

    private void handleCenterHello(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PControl.CenterHello hello = P2PControl.CenterHello.parseFrom(wrapper.getData());
            if (!hello.hasBody()) {
                return;
            }
            P2PControl.CenterHelloBody body = hello.getBody();
            byte[] pub = body.getPubkeySpkiDer().toByteArray();
            byte[] nodeKey = sha256(pub);
            if (nodeKey.length != 32) {
                return;
            }

            RegisteredUsers.Entry reg = users.getByNodeKey(nodeKey);
            if (reg == null || reg.nodeId64() != body.getNodeId64()) {
                return;
            }
            if (!reg.enabled()) {
                return;
            }
            if (!Arrays.equals(reg.pubkeySpkiDer(), pub)) {
                return;
            }
            byte[] signed = body.toByteArray();
            boolean sigOk = RsaSig.verifySha256WithRsa(pub, signed, hello.getSignature().toByteArray());
            if (!sigOk) {
                return;
            }
            String cryptoMode = body.getCryptoMode();
            if (!reg.allowedCryptoModes().isEmpty()) {
                boolean allowed = false;
                for (String s : reg.allowedCryptoModes()) {
                    if (s != null && !s.isBlank() && s.equals(cryptoMode)) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    return;
                }
            }
            System.out.println("join ok nodeId64=" + body.getNodeId64());

            P2PControl.Endpoint observed = observedEndpoint(ctx);
            long now = System.currentTimeMillis();
            long expiresAt = now + ttlSeconds * 1000L;
            List<P2PControl.Endpoint> endpoints = mergeEndpoints(observed, body.getReportedEndpointsList());
            P2PControl.NodeCaps caps = body.hasCaps() ? body.getCaps() : null;
            presence.put(nodeKey, body.getNodeId64(), endpoints, caps, expiresAt, ctx);
            this.selfNodeKey32 = Arrays.copyOf(nodeKey, nodeKey.length);
            this.selfNodeId64 = body.getNodeId64();

            P2PControl.CenterHelloAck ack = P2PControl.CenterHelloAck.newBuilder()
                .setNodeKey(ByteString.copyFrom(nodeKey))
                .setObservedEndpoint(observed)
                .setTtlSeconds(ttlSeconds)
                .setServerTimeMs(now)
                .build();

            P2PWrapperOuterClass.P2PWrapper resp = P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(-11002)
                .setData(ack.toByteString())
                .build();
            writeEncrypted(ctx, resp);

            ctx.executor().schedule(() -> presence.get(nodeKey), ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
        }
    }

    private void handleGetNode(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PControl.GetNode req = P2PControl.GetNode.parseFrom(wrapper.getData());
            byte[] nodeKey = req.getNodeKey().toByteArray();
            PresenceStore.Presence p = nodeKey.length == 32 ? presence.get(nodeKey) : null;
            if (p == null && req.getNodeId64() != 0) {
                p = presence.getByNodeId(req.getNodeId64());
            }
            P2PControl.GetNodeAck.Builder ack = P2PControl.GetNodeAck.newBuilder();
            if (p != null && p.expiresAtMs() > System.currentTimeMillis()) {
                ack.setFound(true)
                    .setNodeKey(ByteString.copyFrom(p.nodeKey32()))
                    .setNodeId64(p.nodeId64())
                    .setExpiresAtMs(p.expiresAtMs());
                for (P2PControl.Endpoint e : p.endpoints()) {
                    ack.addEndpoints(e);
                }
                if (p.caps() != null) {
                    ack.setCaps(p.caps());
                }
            } else {
                ack.setFound(false);
            }

            P2PWrapperOuterClass.P2PWrapper resp = P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(-11011)
                .setData(ack.build().toByteString())
                .build();
            writeEncrypted(ctx, resp);
        } catch (Exception e) {
        }
    }

    private void handleRelayData(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            P2PControl.RelayData req = P2PControl.RelayData.parseFrom(wrapper.getData());
            if (selfNodeKey32 == null || selfNodeKey32.length != 32) {
                return;
            }
            if (!Arrays.equals(req.getSourceNodeKey().toByteArray(), selfNodeKey32)) {
                return;
            }
            if (req.getSourceNodeId64() != 0 && selfNodeId64 != 0 && req.getSourceNodeId64() != selfNodeId64) {
                return;
            }
            byte[] targetKey = req.getTargetNodeKey().toByteArray();
            PresenceStore.Presence p = targetKey.length == 32 ? presence.get(targetKey) : null;
            if (p == null && req.getTargetNodeId64() != 0) {
                p = presence.getByNodeId(req.getTargetNodeId64());
            }
            if (p != null && p.ctx() != null && p.ctx().channel().isActive()) {
                // Forward the same wrapper to the target node
                P2PWrapperOuterClass.P2PWrapper fwd = P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(-11012)
                    .setData(req.toByteString())
                    .build();
                // We need to write to p.ctx(), but encrypted with p's channel state!
                // Wait, writeEncrypted uses `this.offset` and `this.provider` which are bound to the current handler instance.
                // We can't just write to p.ctx() directly using the current handler's method.
                // We should fire a user event or just pass it to the pipeline.
                // Wait, CenterServerHandler handles writeEncrypted internally.
                // So if we get the CenterServerHandler instance from p.ctx().pipeline(), we can call writeEncrypted.
                CenterServerHandler targetHandler = p.ctx().pipeline().get(CenterServerHandler.class);
                if (targetHandler != null) {
                    targetHandler.writeEncrypted(p.ctx(), fwd);
                }
            }
        } catch (Exception e) {
        }
    }

    private void handleConnectHint(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        try {
            if (selfNodeKey32 == null || selfNodeKey32.length != 32) {
                return;
            }
            PresenceStore.Presence source = presence.get(selfNodeKey32);
            if (source == null || source.expiresAtMs() <= System.currentTimeMillis()) {
                return;
            }

            P2PControl.ConnectHint req = P2PControl.ConnectHint.parseFrom(wrapper.getData());
            byte[] targetKey = req.getTargetNodeKey().toByteArray();
            PresenceStore.Presence target = targetKey.length == 32 ? presence.get(targetKey) : null;
            if (target == null && req.getTargetNodeId64() != 0) {
                target = presence.getByNodeId(req.getTargetNodeId64());
            }

            long token = rnd.nextLong();

            P2PControl.ConnectHintAck.Builder ack = P2PControl.ConnectHintAck.newBuilder()
                .setToken(token);
            if (target != null && target.expiresAtMs() > System.currentTimeMillis()) {
                ack.setFound(true)
                    .setTargetNodeId64(target.nodeId64())
                    .setTargetNodeKey(ByteString.copyFrom(target.nodeKey32()));
                for (P2PControl.Endpoint e : target.endpoints()) {
                    ack.addTargetEndpoints(e);
                }
            } else {
                ack.setFound(false);
            }

            P2PWrapperOuterClass.P2PWrapper resp = P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(-11030)
                .setData(ack.build().toByteString())
                .build();
            writeEncrypted(ctx, resp);

            if (target != null && target.ctx() != null && target.ctx().channel().isActive() && target.expiresAtMs() > System.currentTimeMillis()) {
                P2PControl.IncomingHint.Builder ih = P2PControl.IncomingHint.newBuilder()
                    .setSourceNodeId64(source.nodeId64())
                    .setSourceNodeKey(ByteString.copyFrom(source.nodeKey32()))
                    .setToken(token);
                for (P2PControl.Endpoint e : source.endpoints()) {
                    ih.addSourceEndpoints(e);
                }

                P2PWrapperOuterClass.P2PWrapper fwd = P2PWrapperOuterClass.P2PWrapper.newBuilder()
                    .setSeq(wrapper.getSeq())
                    .setCommand(-11031)
                    .setData(ih.build().toByteString())
                    .build();

                CenterServerHandler targetHandler = target.ctx().pipeline().get(CenterServerHandler.class);
                if (targetHandler != null) {
                    targetHandler.writeEncrypted(target.ctx(), fwd);
                }
            }
        } catch (Exception e) {
        }
    }

    private static byte[] sha256(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(b);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<P2PControl.Endpoint> mergeEndpoints(P2PControl.Endpoint observed, List<P2PControl.Endpoint> reported) {
        LinkedHashMap<String, P2PControl.Endpoint> map = new LinkedHashMap<>();
        if (observed != null) {
            map.put(observed.getTransport() + "|" + observed.getAddr(), observed);
        }
        if (reported != null) {
            for (P2PControl.Endpoint e : reported) {
                if (e == null) {
                    continue;
                }
                String k = e.getTransport() + "|" + e.getAddr();
                map.putIfAbsent(k, e);
            }
        }
        return List.copyOf(map.values());
    }

    private P2PControl.Endpoint observedEndpoint(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress a) {
            return P2PControl.Endpoint.newBuilder().setTransport("ws").setAddr(a.getAddress().getHostAddress()).build();
        }
        return P2PControl.Endpoint.newBuilder().setTransport("ws").setAddr(String.valueOf(ctx.channel().remoteAddress())).build();
    }

    private void writePlain(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        byte[] plain = P2PWrapperCodec.encode(wrapper);
        WireHeader h = new WireHeader(plain.length, magic, version, flagsPlain);
        byte[] ws = FrameCodec.encode(h, plain);
        ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(ws)));
    }

    private void writeEncrypted(ChannelHandlerContext ctx, P2PWrapperOuterClass.P2PWrapper wrapper) {
        byte[] plain = P2PWrapperCodec.encode(wrapper);
        byte[] cipher = XorCipher.xorWithKeyFile(plain, provider, keyId32, offset);
        WireHeader h = new WireHeader(cipher.length, magic, version, flagsEncrypted);
        byte[] ws = FrameCodec.encode(h, cipher);
        ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(ws)));
    }
}
