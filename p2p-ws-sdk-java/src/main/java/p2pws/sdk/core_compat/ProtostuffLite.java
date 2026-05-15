package p2pws.sdk.core_compat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class ProtostuffLite {
    private ProtostuffLite() {
    }

    static byte[] encodeP2PWrapper(int seq, int commandOrdinal, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeKey(out, 1, 0);
        Varint.writeI32(out, seq);
        writeKey(out, 2, 0);
        Varint.writeI32(out, commandOrdinal);
        writeKey(out, 3, 2);
        byte[] d = data == null ? new byte[0] : data;
        Varint.writeU64(out, d.length);
        out.writeBytes(d);
        return out.toByteArray();
    }

    static DecodedWrapper decodeP2PWrapper(byte[] payload) {
        int[] pos = new int[] {0};
        int seq = 0;
        int cmd = 0;
        byte[] data = new byte[0];
        while (pos[0] < payload.length) {
            long key = Varint.readU64(payload, pos);
            int field = (int) (key >>> 3);
            int wt = (int) (key & 0x7);
            if (field == 1 && wt == 0) {
                seq = (int) Varint.readU64(payload, pos);
                continue;
            }
            if (field == 2 && wt == 0) {
                cmd = (int) Varint.readU64(payload, pos);
                continue;
            }
            if (field == 3 && wt == 2) {
                data = readBytes(payload, pos);
                continue;
            }
            skipField(payload, pos, wt);
        }
        return new DecodedWrapper(seq, cmd, data);
    }

    static byte[] encodeHandshakeRequest(String userId, long timestampMs, byte[] nonce, int xorKeyLength, byte[] encryptedXorKey, byte[] signature) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (userId != null && !userId.isEmpty()) {
            writeKey(out, 1, 2);
            writeBytesRaw(out, userId.getBytes(StandardCharsets.UTF_8));
        }
        writeKey(out, 2, 0);
        Varint.writeU64(out, timestampMs);
        if (nonce != null && nonce.length > 0) {
            writeKey(out, 3, 2);
            writeBytesRaw(out, nonce);
        }
        writeKey(out, 4, 0);
        Varint.writeI32(out, xorKeyLength);
        writeKey(out, 5, 2);
        writeBytesRaw(out, encryptedXorKey == null ? new byte[0] : encryptedXorKey);
        writeKey(out, 6, 2);
        writeBytesRaw(out, signature == null ? new byte[0] : signature);
        return out.toByteArray();
    }

    static HandshakeResponse decodeHandshakeResponse(byte[] payload) {
        int[] pos = new int[] {0};
        boolean ok = false;
        String error = "";
        while (pos[0] < payload.length) {
            long key = Varint.readU64(payload, pos);
            int field = (int) (key >>> 3);
            int wt = (int) (key & 0x7);
            if (field == 1 && wt == 0) {
                ok = Varint.readU64(payload, pos) != 0;
                continue;
            }
            if (field == 2 && wt == 2) {
                error = new String(readBytes(payload, pos), StandardCharsets.UTF_8);
                continue;
            }
            skipField(payload, pos, wt);
        }
        return new HandshakeResponse(ok, error);
    }

    static byte[] encodeLoginRequest(String userId, long timestampMs, byte[] signature) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (userId != null && !userId.isEmpty()) {
            writeKey(out, 1, 2);
            writeBytesRaw(out, userId.getBytes(StandardCharsets.UTF_8));
        }
        writeKey(out, 2, 0);
        Varint.writeU64(out, timestampMs);
        writeKey(out, 3, 2);
        writeBytesRaw(out, signature == null ? new byte[0] : signature);
        return out.toByteArray();
    }

    static LoginResponse decodeLoginResponse(byte[] payload) {
        int[] pos = new int[] {0};
        boolean ok = false;
        String error = "";
        while (pos[0] < payload.length) {
            long key = Varint.readU64(payload, pos);
            int field = (int) (key >>> 3);
            int wt = (int) (key & 0x7);
            if (field == 1 && wt == 0) {
                ok = Varint.readU64(payload, pos) != 0;
                continue;
            }
            if (field == 2 && wt == 2) {
                error = new String(readBytes(payload, pos), StandardCharsets.UTF_8);
                continue;
            }
            skipField(payload, pos, wt);
        }
        return new LoginResponse(ok, error);
    }

    static void xorInPlace(byte[] data, byte[] key) {
        if (data == null || key == null || key.length == 0) {
            return;
        }
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (data[i] ^ key[i % key.length]);
        }
    }

    private static void writeKey(ByteArrayOutputStream out, int fieldNo, int wt) {
        Varint.writeU64(out, ((long) fieldNo << 3) | (long) (wt & 0x7));
    }

    private static void writeBytesRaw(ByteArrayOutputStream out, byte[] bytes) {
        Varint.writeU64(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static byte[] readBytes(byte[] payload, int[] pos) {
        long ln = Varint.readU64(payload, pos);
        if (ln < 0 || ln > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("bad len");
        }
        int len = (int) ln;
        if (pos[0] + len > payload.length) {
            throw new IllegalArgumentException("truncated bytes");
        }
        byte[] out = Arrays.copyOfRange(payload, pos[0], pos[0] + len);
        pos[0] += len;
        return out;
    }

    private static void skipField(byte[] payload, int[] pos, int wt) {
        if (wt == 0) {
            Varint.readU64(payload, pos);
            return;
        }
        if (wt == 1) {
            pos[0] += 8;
            if (pos[0] > payload.length) {
                throw new IllegalArgumentException("truncated fixed64");
            }
            return;
        }
        if (wt == 2) {
            long ln = Varint.readU64(payload, pos);
            pos[0] += (int) ln;
            if (ln < 0 || pos[0] > payload.length) {
                throw new IllegalArgumentException("truncated len");
            }
            return;
        }
        if (wt == 5) {
            pos[0] += 4;
            if (pos[0] > payload.length) {
                throw new IllegalArgumentException("truncated fixed32");
            }
            return;
        }
        throw new IllegalArgumentException("unsupported wireType=" + wt);
    }

    record DecodedWrapper(int seq, int commandOrdinal, byte[] data) {
    }

    record HandshakeResponse(boolean ok, String error) {
    }

    record LoginResponse(boolean ok, String error) {
    }
}

