package com.q3lives.ds.registry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DsRegistryMultiSzCodec {

    private static final int MAX_COUNT = 0xFFFF;
    private static final int MAX_BYTES_PER_ITEM = 0x7FFF;

    private DsRegistryMultiSzCodec() {}

    static byte[] encode(List<String> values) {
        List<String> src = (values == null) ? Collections.emptyList() : values;
        int count = src.size();
        if (count > MAX_COUNT) {
            throw new IllegalArgumentException("REG_MULTI_SZ 项数超过上限 65535，实际：" + count);
        }
        List<byte[]> encoded = new ArrayList<>(count);
        int total = 2;
        for (String s : src) {
            byte[] b = (s == null) ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
            if (b.length > MAX_BYTES_PER_ITEM) {
                throw new IllegalArgumentException("REG_MULTI_SZ 单元素 UTF-8 字节超过 32767，实际：" + b.length);
            }
            encoded.add(b);
            total += 2 + b.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) count);
        for (byte[] b : encoded) {
            buf.putShort((short) b.length);
            buf.put(b);
        }
        return buf.array();
    }

    static List<String> decode(byte[] payload) {
        if (payload == null || payload.length < 2) return Collections.emptyList();
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int count = Short.toUnsignedInt(buf.getShort());
        if (count == 0) return Collections.emptyList();
        if (count > MAX_COUNT) {
            throw new IllegalArgumentException("REG_MULTI_SZ 损坏：count=" + count + " 超过 65535");
        }
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (buf.remaining() < 2) {
                throw new IllegalArgumentException("REG_MULTI_SZ 损坏：位置 " + buf.position() + " 剩余不足读取 item length");
            }
            int len = Short.toUnsignedInt(buf.getShort());
            if (len > MAX_BYTES_PER_ITEM) {
                throw new IllegalArgumentException("REG_MULTI_SZ 损坏：item[" + i + "] len=" + len + " 超过 32767");
            }
            if (buf.remaining() < len) {
                throw new IllegalArgumentException("REG_MULTI_SZ 损坏：item[" + i + "] 长度 " + len + " 超出 payload 剩余 " + buf.remaining());
            }
            byte[] b = new byte[len];
            buf.get(b);
            out.add(new String(b, StandardCharsets.UTF_8));
        }
        return out;
    }
}
