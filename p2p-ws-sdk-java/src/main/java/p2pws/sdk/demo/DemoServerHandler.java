package p2pws.sdk.demo;

import com.google.protobuf.ByteString;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import p2pws.P2PControl;
import p2pws.P2PWrapperOuterClass;
import p2pws.sdk.FrameCodec;
import p2pws.sdk.KeyFileProvider;
import p2pws.sdk.P2PWrapperCodec;
import p2pws.sdk.RsaOaep;
import p2pws.sdk.WireHeader;
import p2pws.sdk.WireFrame;
import p2pws.sdk.XorCipher;

public final class DemoServerHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

    private final KeyFileProvider provider;
    private final byte[] keyId32;
    private final long keyLen;
    private final int magic;
    private final int version;
    private final int flagsPlain;
    private final int flagsEncrypted;
    private final int maxFramePayload;
    private final SecureRandom rnd = new SecureRandom();

    private long offset = -1;
    private boolean encrypted = false;
    private boolean cryptUpdated = false;

    public DemoServerHandler(KeyFileProvider provider, byte[] keyId32, long keyLen, int magic, int version, int flagsPlain, int flagsEncrypted, int maxFramePayload) {
        this.provider = provider;
        this.keyId32 = Arrays.copyOf(keyId32, keyId32.length);
        this.keyLen = keyLen;
        this.magic = magic;
        this.version = version;
        this.flagsPlain = flagsPlain;
        this.flagsEncrypted = flagsEncrypted;
        this.maxFramePayload = maxFramePayload;
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
        if (cmd == -10010) {
            return;
        }
        if (cmd == 1) {
            P2PWrapperOuterClass.P2PWrapper resp = P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(wrapper.getCommand())
                .setData(wrapper.getData())
                .build();
            writeEncrypted(ctx, resp);
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

            PublicKey clientPub = RsaPublicKeyDecoder.fromSpkiDer(hand.getClientPubkey().toByteArray());
            byte[] encryptedAck = RsaOaep.encryptSha256(clientPub, ackPlain.toByteArray());

            P2PWrapperOuterClass.P2PWrapper resp = P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(wrapper.getSeq())
                .setCommand(-10002)
                .setData(ByteString.copyFrom(encryptedAck))
                .build();

            writePlain(ctx, resp);
            this.encrypted = true;

            if (!cryptUpdated) {
                cryptUpdated = true;
                ctx.executor().schedule(() -> sendCryptUpdate(ctx, wrapper.getSeq()), 100, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            ctx.close();
        }
    }

    private void sendCryptUpdate(ChannelHandlerContext ctx, int seq) {
        try {
            if (!encrypted || offset < 0) {
                return;
            }
            long maxPayload = maxFramePayload;
            long maxOffset = keyLen - maxPayload;
            if (maxOffset <= 0) {
                return;
            }
            long newOffset = (Integer.toUnsignedLong(rnd.nextInt()) % maxOffset);
            this.offset = newOffset;

            P2PControl.CryptUpdate cu = P2PControl.CryptUpdate.newBuilder()
                .setKeyId(ByteString.copyFrom(keyId32))
                .setOffset((int) newOffset)
                .setEffectiveFromSeq(0)
                .build();
            P2PWrapperOuterClass.P2PWrapper msg = P2PWrapperOuterClass.P2PWrapper.newBuilder()
                .setSeq(seq)
                .setCommand(-10010)
                .setData(cu.toByteString())
                .build();
            writeEncrypted(ctx, msg);
        } catch (Exception e) {
        }
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
