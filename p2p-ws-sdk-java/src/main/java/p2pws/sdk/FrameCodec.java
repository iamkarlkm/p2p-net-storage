package p2pws.sdk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class FrameCodec {

    public static final int HEADER_LEN = 8;

    private FrameCodec() {
    }

    public static WireFrame decode(byte[] wsBinaryPayload) {
        if (wsBinaryPayload == null || wsBinaryPayload.length < HEADER_LEN) {
            throw new IllegalArgumentException("invalid frame");
        }
        ByteBuffer buf = ByteBuffer.wrap(wsBinaryPayload).order(ByteOrder.BIG_ENDIAN);
        long length = Integer.toUnsignedLong(buf.getInt());
        int magic = Short.toUnsignedInt(buf.getShort());
        int version = Byte.toUnsignedInt(buf.get());
        int flags = Byte.toUnsignedInt(buf.get());
        byte[] cipher = new byte[wsBinaryPayload.length - HEADER_LEN];
        buf.get(cipher);
        return new WireFrame(new WireHeader(length, magic, version, flags), cipher);
    }

    public static byte[] encode(WireHeader header, byte[] cipherPayload) {
        if (header == null) {
            throw new IllegalArgumentException("header required");
        }
        if (cipherPayload == null) {
            cipherPayload = new byte[0];
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN + cipherPayload.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt((int) header.length());
        buf.putShort((short) header.magic());
        buf.put((byte) header.version());
        buf.put((byte) header.flags());
        buf.put(cipherPayload);
        return buf.array();
    }
}

