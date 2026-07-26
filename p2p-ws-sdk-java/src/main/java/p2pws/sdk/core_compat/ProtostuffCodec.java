package p2pws.sdk.core_compat;

import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;

final class ProtostuffCodec {
    private static final ThreadLocal<LinkedBuffer> BUFFERS = new ThreadLocal<>();

    private ProtostuffCodec() {
    }

    static byte[] serialize(Object obj) {
        if (obj == null) {
            return new byte[0];
        }
        @SuppressWarnings("unchecked")
        Schema<Object> schema = (Schema<Object>) RuntimeSchema.getSchema(obj.getClass());
        LinkedBuffer buffer = BUFFERS.get();
        if (buffer == null) {
            buffer = LinkedBuffer.allocate(512);
            BUFFERS.set(buffer);
        }
        try {
            return ProtostuffIOUtil.toByteArray(obj, schema, buffer);
        } finally {
            buffer.clear();
        }
    }

    static <T> T deserialize(Class<T> clazz, byte[] bytes) {
        try {
            T msg = clazz.getDeclaredConstructor().newInstance();
            Schema<T> schema = RuntimeSchema.getSchema(clazz);
            ProtostuffIOUtil.mergeFrom(bytes == null ? new byte[0] : bytes, msg, schema);
            return msg;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
