package p2pws.sdk.core_compat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

final class CoreFrameCodec {
    private CoreFrameCodec() {
    }

    static byte[] encode(int magic, byte[] payload) {
        int len = payload == null ? 0 : payload.length;
        ByteBuffer buf = ByteBuffer.allocate(8 + len).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(len);
        buf.putInt(magic);
        if (len > 0) {
            buf.put(payload);
        }
        return buf.array();
    }

    static Decoded decode(byte[] frame) {
        List<Decoded> decoded = decodeAll(frame);
        if (decoded.size() != 1) {
            throw new IllegalArgumentException("expected exactly 1 frame, got " + decoded.size());
        }
        return decoded.get(0);
    }

    static List<Decoded> decodeAll(byte[] frame) {
        if (frame == null || frame.length < 8) {
            throw new IllegalArgumentException("frame too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        List<Decoded> output = new ArrayList<>();
        while (buf.remaining() > 0) {
            if (buf.remaining() < 8) {
                throw new IllegalArgumentException("trailing bytes: " + buf.remaining());
            }
            int len = buf.getInt();
            int magic = buf.getInt();
            if (len < 0 || buf.remaining() < len) {
                throw new IllegalArgumentException("bad length");
            }
            byte[] payload = new byte[len];
            if (len > 0) {
                buf.get(payload);
            }
            output.add(new Decoded(magic, payload));
        }
        return output;
    }

    record Decoded(int magic, byte[] payload) {
    }
}
