

package io.protostuff;

import io.netty.buffer.ByteBuf;
import java.io.IOException;

/**
 * The flexible output for outputs that use {@link WriteSessionWithByteBuf}.
 * 
 * @author David Yu
 * @created Sep 20, 2010
 */
public enum WriteSinkWithByteBuf
{
    BUFFERED
    {
//        @Override
//        public ByteBuf drain(final WriteSessionWithByteBuf session,
//                final ByteBuf byteBuf) throws IOException
//        {
//            // grow
//            return new ByteBuf(session.nextBufferSize, byteBuf);
//        }

        @Override
        public ByteBuf writeByteArrayB64(final byte[] value,
                final int offset, final int valueLen,
                final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException
        {
            return B64CodeWithByteBuf.encode(value, offset, valueLen, session, byteBuf);
        }

        @Override
        public ByteBuf writeByteArray(final byte[] value,
                final int offset, final int valueLen,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            if (valueLen == 0)
                return byteBuf;

            session.size += valueLen;
            
            byteBuf.writeBytes(value, offset, valueLen);
            return byteBuf;
        }

        @Override
        public ByteBuf writeByte(final byte value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size++;

            byteBuf.writeByte(value);

            return byteBuf;
        }

        @Override
        public ByteBuf writeInt16(final int value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size += 2;

            

           //byteBuf.writeByte((byte) ((value >>> 8) & 0xFF));
        //byteBuf.writeByte((byte) value);
            byteBuf.writeShort(value);
            return byteBuf;
        }

        @Override
        public ByteBuf writeInt16LE(final int value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size += 2;
            //byteBuf.writeByte((byte) value);
             //byteBuf.writeByte((byte) ((value >>> 8) & 0xFF));
              byteBuf.writeShortLE(value);
            return byteBuf;
        }

        @Override
        public ByteBuf writeInt32(final int value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size += 4;

              byteBuf.writeInt(value);

            return byteBuf;
        }

        @Override
        public ByteBuf writeInt64(final long value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size += 8;
            byteBuf.writeLong(value);

            return byteBuf;
        }

        @Override
        public ByteBuf writeInt32LE(final int value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size += 4;

            byteBuf.writeIntLE(value);

            return byteBuf;
        }

        @Override
        public ByteBuf writeInt64LE(final long value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size += 8;

             byteBuf.writeLongLE(value);

            return byteBuf;
        }

        @Override
        public ByteBuf writeVarInt32(int value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            while (true)
            {
                session.size++;
                if ((value & ~0x7F) == 0)
                {
                    byteBuf.writeByte((byte) value);
                    return byteBuf;
                }

                byteBuf.writeByte((byte) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }

        @Override
        public ByteBuf writeVarInt64(long value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            while (true)
            {
                session.size++;
                
                if ((value & ~0x7FL) == 0)
                {
                    byteBuf.writeByte((byte) value);
                    return byteBuf;
                }
                
                byteBuf.writeByte((byte) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }

        @Override
        public ByteBuf writeStrFromInt(final int value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            return StringSerializerWithByteBuf.writeInt(value, session, byteBuf);
        }

        @Override
        public ByteBuf writeStrFromLong(final long value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            return StringSerializerWithByteBuf.writeLong(value, session, byteBuf);
        }

        @Override
        public ByteBuf writeStrFromFloat(final float value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            return StringSerializerWithByteBuf.writeFloat(value, session, byteBuf);
        }

        @Override
        public ByteBuf writeStrFromDouble(final double value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            return StringSerializerWithByteBuf.writeDouble(value, session, byteBuf);
        }

        @Override
        public ByteBuf writeStrAscii(final CharSequence value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            return StringSerializerWithByteBuf.writeAscii(value, session, byteBuf);
        }

        @Override
        public ByteBuf writeStrUTF8(final CharSequence value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            return StringSerializerWithByteBuf.writeUTF8(value, session, byteBuf);
        }

        @Override
        public ByteBuf writeStrUTF8VarDelimited(final CharSequence value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            return StringSerializerWithByteBuf.writeUTF8VarDelimited(value, session, byteBuf);
        }

        @Override
        public ByteBuf writeStrUTF8FixedDelimited(final CharSequence value,
                final boolean littleEndian, final WriteSessionWithByteBuf session, ByteBuf byteBuf)
                throws IOException
        {
            return StringSerializerWithByteBuf.writeUTF8FixedDelimited(value, littleEndian, session,
                    byteBuf);
        }
    },
    STREAMED
    {
//        @Override
//        public ByteBuf drain(final WriteSessionWithByteBuf session,
//                final ByteBuf byteBuf) throws IOException
//        {
//            // flush and reset
//            byteBuf.offset = session.flush(byteBuf.buffer, byteBuf.start, byteBuf.offset - byteBuf.start);
//            return byteBuf;
//        }

        @Override
        public ByteBuf writeByteArrayB64(final byte[] value,
                final int offset, final int valueLen,
                final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException
        {
            B64CodeWithByteBuf.encode(value, offset, valueLen, session, byteBuf);
            session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeByteArray(final byte[] value,
                final int offset, final int valueLen,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            if (valueLen == 0)
                return byteBuf;

            session.size += valueLen;
            
            byteBuf.writeBytes(value, offset, valueLen);
            session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeByte(final byte value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size++;

            byteBuf.writeByte(value);
            session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeInt16(final int value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size += 2;

           //byteBuf.writeByte((byte) ((value >>> 8) & 0xFF));
        //byteBuf.writeByte((byte) value);
            byteBuf.writeShort(value);
            session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeInt16LE(final int value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size += 2;
            //byteBuf.writeByte((byte) value);
             //byteBuf.writeByte((byte) ((value >>> 8) & 0xFF));
              byteBuf.writeShortLE(value);
              session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeInt32(final int value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size += 4;

              byteBuf.writeInt(value);
session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeInt64(final long value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size += 8;
            byteBuf.writeLong(value);
session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeInt32LE(final int value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size += 4;

            byteBuf.writeIntLE(value);
session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeInt64LE(final long value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            session.size += 8;

             byteBuf.writeLongLE(value);
session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeVarInt32(int value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            while (true)
            {
                session.size++;
                if ((value & ~0x7F) == 0)
                {
                    byteBuf.writeByte((byte) value);
                    session.flushStream();
                    return byteBuf;
                }

                byteBuf.writeByte((byte) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            
        }

        @Override
        public ByteBuf writeVarInt64(long value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            while (true)
            {
                session.size++;
                
                if ((value & ~0x7FL) == 0)
                {
                    byteBuf.writeByte((byte) value);
                    session.flushStream();
                    return byteBuf;
                }
                
                byteBuf.writeByte((byte) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }

        @Override
        public ByteBuf writeStrFromInt(final int value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            StringSerializerWithByteBuf.writeInt(value, session, byteBuf);
            session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeStrFromLong(final long value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            StringSerializerWithByteBuf.writeLong(value, session, byteBuf);
            session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeStrFromFloat(final float value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            StringSerializerWithByteBuf.writeFloat(value, session, byteBuf);
            session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeStrFromDouble(final double value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            StringSerializerWithByteBuf.writeDouble(value, session, byteBuf);
            session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeStrAscii(final CharSequence value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            StringSerializerWithByteBuf.writeAscii(value, session, byteBuf);
            session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeStrUTF8(final CharSequence value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            StringSerializerWithByteBuf.writeUTF8(value, session, byteBuf);
            session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeStrUTF8VarDelimited(final CharSequence value,
                final WriteSessionWithByteBuf session, ByteBuf byteBuf) throws IOException
        {
            StringSerializerWithByteBuf.writeUTF8VarDelimited(value, session, byteBuf);
            session.flushStream();
            return byteBuf;
        }

        @Override
        public ByteBuf writeStrUTF8FixedDelimited(final CharSequence value,
                final boolean littleEndian, final WriteSessionWithByteBuf session, ByteBuf byteBuf)
                throws IOException
        {
            StringSerializerWithByteBuf.writeUTF8FixedDelimited(value, littleEndian, session,
                    byteBuf);
            session.flushStream();
            return byteBuf;
        }
    };

//    public abstract ByteBuf drain(final WriteSessionWithByteBuf session,
//            final ByteBuf byteBuf) throws IOException;

    public final ByteBuf writeByteArrayB64(final byte[] value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException
    {
        return writeByteArrayB64(value, 0, value.length, session, byteBuf);
        
    }

    public abstract ByteBuf writeByteArrayB64(final byte[] value,
            final int offset, final int length, final WriteSessionWithByteBuf session, final ByteBuf byteBuf)
            throws IOException;

    public final ByteBuf writeByteArray(final byte[] value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException
    {
        return writeByteArray(value, 0, value.length, session, byteBuf);
    }

    public abstract ByteBuf writeByteArray(final byte[] value,
            final int offset, final int length, final WriteSessionWithByteBuf session, final ByteBuf byteBuf)
            throws IOException;

    public abstract ByteBuf writeByte(final byte value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    // public abstract ByteBuf writeBool(final boolean value,
    // final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeInt32(final int value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeInt64(final long value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public final ByteBuf writeFloat(final float value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException
    {
        return writeInt32(Float.floatToRawIntBits(value), session, byteBuf);
    }

    public final ByteBuf writeDouble(final double value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException
    {
        return writeInt64(Double.doubleToRawLongBits(value), session, byteBuf);
    }

    public abstract ByteBuf writeInt16(final int value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeInt16LE(final int value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeInt32LE(final int value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeInt64LE(final long value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public final ByteBuf writeFloatLE(final float value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException
    {
        return writeInt32LE(Float.floatToRawIntBits(value), session, byteBuf);
    }

    public final ByteBuf writeDoubleLE(final double value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException
    {
        return writeInt64LE(Double.doubleToRawLongBits(value), session, byteBuf);
    }

    public abstract ByteBuf writeVarInt32(final int value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeVarInt64(final long value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeStrFromInt(final int value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeStrFromLong(final long value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeStrFromFloat(final float value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeStrFromDouble(final double value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeStrAscii(final CharSequence value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeStrUTF8(final CharSequence value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeStrUTF8VarDelimited(final CharSequence value,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException;

    public abstract ByteBuf writeStrUTF8FixedDelimited(final CharSequence value,
            final boolean littleEndian, final WriteSessionWithByteBuf session,
            final ByteBuf byteBuf) throws IOException;


}
