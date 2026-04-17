package io.protostuff;

import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;
import java.io.UTFDataFormatException;
import java.io.UnsupportedEncodingException;

import static java.lang.Character.MIN_HIGH_SURROGATE;
import static java.lang.Character.MIN_LOW_SURROGATE;
import static java.lang.Character.MIN_SUPPLEMENTARY_CODE_POINT;
import java.nio.charset.Charset;
import org.apache.commons.codec.Charsets;

/**
 * UTF-8 String serialization
 *
 * @author David Yu
 * @created Feb 4, 2010
 */
public final class StringSerializerWithByteBuf
{

    private StringSerializerWithByteBuf()
    {
    }

    /**
     * From {@link java.lang.Integer#toString(int)}
     */
    static final int[] sizeTable = new int[] {
            9, 99, 999, 9999, 99999, 999999, 9999999, 99999999, 999999999, Integer.MAX_VALUE
    };

    static final char[] DigitTens = {
            '0', '0', '0', '0', '0', '0', '0', '0', '0', '0',
            '1', '1', '1', '1', '1', '1', '1', '1', '1', '1',
            '2', '2', '2', '2', '2', '2', '2', '2', '2', '2',
            '3', '3', '3', '3', '3', '3', '3', '3', '3', '3',
            '4', '4', '4', '4', '4', '4', '4', '4', '4', '4',
            '5', '5', '5', '5', '5', '5', '5', '5', '5', '5',
            '6', '6', '6', '6', '6', '6', '6', '6', '6', '6',
            '7', '7', '7', '7', '7', '7', '7', '7', '7', '7',
            '8', '8', '8', '8', '8', '8', '8', '8', '8', '8',
            '9', '9', '9', '9', '9', '9', '9', '9', '9', '9',
    };

    static final char[] DigitOnes = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    };

    static final char[] digits = {
            '0', '1', '2', '3', '4', '5',
            '6', '7', '8', '9', 'a', 'b',
            'c', 'd', 'e', 'f', 'g', 'h',
            'i', 'j', 'k', 'l', 'm', 'n',
            'o', 'p', 'q', 'r', 's', 't',
            'u', 'v', 'w', 'x', 'y', 'z'
    };

    static final byte[] INT_MIN_VALUE = new byte[] {
            (byte) '-',
            (byte) '2',
            (byte) '1', (byte) '4', (byte) '7',
            (byte) '4', (byte) '8', (byte) '3',
            (byte) '6', (byte) '4', (byte) '8'
    };

    static final byte[] LONG_MIN_VALUE = new byte[] {
            (byte) '-',
            (byte) '9',
            (byte) '2', (byte) '2', (byte) '3',
            (byte) '3', (byte) '7', (byte) '2',
            (byte) '0', (byte) '3', (byte) '6',
            (byte) '8', (byte) '5', (byte) '4',
            (byte) '7', (byte) '7', (byte) '5',
            (byte) '8', (byte) '0', (byte) '8'
    };

    static final int TWO_BYTE_LOWER_LIMIT = 1 << 7;

    static final int ONE_BYTE_EXCLUSIVE = TWO_BYTE_LOWER_LIMIT / 3 + 1;

    static final int THREE_BYTE_LOWER_LIMIT = 1 << 14;

    static final int TWO_BYTE_EXCLUSIVE = THREE_BYTE_LOWER_LIMIT / 3 + 1;

    static final int FOUR_BYTE_LOWER_LIMIT = 1 << 21;

    static final int THREE_BYTE_EXCLUSIVE = FOUR_BYTE_LOWER_LIMIT / 3 + 1;

    static final int FIVE_BYTE_LOWER_LIMIT = 1 << 28;

    static final int FOUR_BYTE_EXCLUSIVE = FIVE_BYTE_LOWER_LIMIT / 3 + 1;

    static void putBytesFromInt(int i, final int offset, final int size, final ByteBuf buf)
    {
        int q, r;
       
        int charPos = offset + size;
        buf.writerIndex(charPos);
        char sign = 0;

        if (i < 0)
        {
            sign = '-';
            i = -i;
        }

        // Generate two digits per iteration
        while (i >= 65536)
        {
            q = i / 100;
            // really: r = i - (q * 100);
            r = i - ((q << 6) + (q << 5) + (q << 2));
            i = q;
            buf.setByte(--charPos,(byte) DigitOnes[r]);
            buf.setByte(--charPos,(byte) DigitTens[r]);
        }

        // Fall thru to fast mode for smaller numbers
        // assert(i <= 65536, i);
        for (;;)
        {
            q = (i * 52429) >>> (16 + 3);
            r = i - ((q << 3) + (q << 1)); // r = i-(q*10) ...
            buf.setByte(--charPos, (byte) digits[r]);
            i = q;
            if (i == 0)
                break;
        }
        if (sign != 0)
        {
            buf.setByte(--charPos,(byte) sign);
        }
    }

    static void putBytesFromLong(long i, final int offset, int size, final ByteBuf buf)
    {
        long q;
        int r;
        int charPos = offset + size;
        buf.writerIndex(charPos);
        char sign = 0;

        if (i < 0)
        {
            sign = '-';
            i = -i;
        }

        // Get 2 digits/iteration using longs until quotient fits into an int
        while (i > Integer.MAX_VALUE)
        {
            q = i / 100;
            // really: r = i - (q * 100);
            r = (int) (i - ((q << 6) + (q << 5) + (q << 2)));
            i = q;
            buf.setByte(--charPos,(byte) DigitOnes[r]);
            buf.setByte(--charPos,(byte) DigitTens[r]);
        }

        // Get 2 digits/iteration using ints
        int q2;
        int i2 = (int) i;
        while (i2 >= 65536)
        {
            q2 = i2 / 100;
            // really: r = i2 - (q * 100);
            r = i2 - ((q2 << 6) + (q2 << 5) + (q2 << 2));
            i2 = q2;
           buf.setByte(--charPos,(byte) DigitOnes[r]);
            buf.setByte(--charPos,(byte) DigitTens[r]);
        }

        // Fall thru to fast mode for smaller numbers
        // assert(i2 <= 65536, i2);
        for (;;)
        {
            q2 = (i2 * 52429) >>> (16 + 3);
            r = i2 - ((q2 << 3) + (q2 << 1)); // r = i2-(q2*10) ...
            buf.setByte(--charPos, (byte) digits[r]);
            i2 = q2;
            if (i2 == 0)
                break;
        }
        if (sign != 0)
        {
            buf.setByte(--charPos,(byte) sign);
        }
    }

    // Requires positive x
    static int stringSize(int x)
    {
        for (int i = 0;; i++)
        {
            if (x <= sizeTable[i])
                return i + 1;
        }
    }

    // Requires positive x
    static int stringSize(long x)
    {
        long p = 10;
        for (int i = 1; i < 19; i++)
        {
            if (x < p)
                return i;
            p = 10 * p;
        }
        return 19;
    }

    /**
     * Writes the stringified int into the {@link ByteBuf}.
     * @param value
     * @param session
     * @param byteBuf
     * @return 
     */
    public static ByteBuf writeInt(final int value, final WriteSessionWithByteBuf session,
            ByteBuf byteBuf)
    {
        if (value == Integer.MIN_VALUE)
        {
            final int valueLen = INT_MIN_VALUE.length;
            

            byteBuf.writeBytes(INT_MIN_VALUE, 0, valueLen);
            session.size += valueLen;

            return byteBuf;
        }

        final int size = (value < 0) ? stringSize(-value) + 1 : stringSize(value);

        putBytesFromInt(value, byteBuf.writerIndex(), size, byteBuf);

        session.size += size;

        return byteBuf;
    }

    /**
     * Writes the stringified long into the {@link ByteBuf}.
     */
    public static ByteBuf writeLong(final long value, final WriteSessionWithByteBuf session,
            ByteBuf byteBuf)
    {
        if (value == Long.MIN_VALUE)
        {
            final int valueLen = LONG_MIN_VALUE.length;
             byteBuf.writeBytes(LONG_MIN_VALUE, 0, valueLen);
            session.size += valueLen;

            return byteBuf;
        }

        final int size = (value < 0) ? stringSize(-value) + 1 : stringSize(value);

        putBytesFromLong(value, byteBuf.writerIndex(), size, byteBuf);
        session.size += size;

        return byteBuf;
    }

    /**
     * Writes the stringified float into the {@link ByteBuf}.TODO - skip string conversion and write directly to
 buffer
     * @param value
     * @param session
     * @param byteBuf
     * @return 
     */
    public static ByteBuf writeFloat(final float value, final WriteSessionWithByteBuf session,
            final ByteBuf byteBuf)
    {
        return writeAscii(Float.toString(value), session, byteBuf);
    }

    /**
     * Writes the stringified double into the {@link ByteBuf}.TODO - skip string conversion and write directly to
 buffer
     * @param value
     * @param session
     * @param byteBuf
     * @return 
     */
    public static ByteBuf writeDouble(final double value, final WriteSessionWithByteBuf session,
            final ByteBuf byteBuf)
    {
        return writeAscii(Double.toString(value), session, byteBuf);
    }

    /**
     * Computes the size of the utf8 string beginning at the specified {@code index} with the specified {@code length}.
     */
    public static int computeUTF8Size(final CharSequence str, final int index, final int len)
    {
        int size = len;
        for (int i = index; i < len; i++)
        {
            final char c = str.charAt(i);
            if (c < 0x0080)
                continue;

            if (c < 0x0800)
                size++;
            else
                size += 2;
        }
        return size;
    }

    /**
     * Slow path. It checks the limit before every write. Shared with StreamedStringSerializer.
     */
    static ByteBuf writeUTF8(final CharSequence str, int i, final int len,
            byte[] buffer, int offset, int limit,
            final WriteSessionWithByteBuf session, ByteBuf byteBuf)
    {
        for (char c = 0;; c = 0)
        {
            while (i != len && offset != limit && (c = str.charAt(i++)) < 0x0080)
                byteBuf.writeByte((byte) c);

            if (i == len && c < 0x0080)
            {
                session.size += byteBuf.writerIndex()-offset;
                return byteBuf;
            }

            

            if (c < 0x0800)
            {
                

                byteBuf.writeByte( (byte) (0xC0 | ((c >> 6) & 0x1F)));

               byteBuf.writeByte( (byte) (0x80 | ((c >> 0) & 0x3F)));
            }
            else if (Character.isHighSurrogate((char) c) && i < len && Character.isLowSurrogate((char) str.charAt(i)))
            {
                // We have a surrogate pair, so use the 4-byte encoding.
               
                int codePoint = Character.toCodePoint((char) c, (char) str.charAt(i));

                byteBuf.writeByte( (byte) (0xF0 | ((codePoint >> 18) & 0x07)));

                

                byteBuf.writeByte( buffer[offset++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F)));

                
                byteBuf.writeByte(  (byte) (0x80 | ((codePoint >> 6) & 0x3F)));

                

                byteBuf.writeByte(  (byte) (0x80 | ((codePoint >> 0) & 0x3F)));

                i++;
            }
            else
            {
                

               byteBuf.writeByte(  buffer[offset++] = (byte) (0xE0 | ((c >> 12) & 0x0F)));

                
                byteBuf.writeByte( buffer[offset++] = (byte) (0x80 | ((c >> 6) & 0x3F)));

                
                byteBuf.writeByte( buffer[offset++] = (byte) (0x80 | ((c >> 0) & 0x3F)));
            }
        }
    }

    /**
     * Fast path. The {@link ByteBuf}'s capacity is >= string length.
     */
    static ByteBuf writeUTF8(final CharSequence str, int i, final int len,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf)
        
        
    {
        //System.out.println("writeUTF8 397 -> "+str);
        int offset = byteBuf.writerIndex();
//        byteBuf.writeBytes(str.subSequence(i, len).toString().getBytes(CharsetUtil.UTF_8));
        byteBuf.writeCharSequence(str.subSequence(i, len), CharsetUtil.UTF_8);
        session.size += byteBuf.writerIndex()-offset;
        return byteBuf;
    }

    /**
     * Writes the utf8-encoded bytes from the string into the {@link ByteBuf}.
     * @param str
     * @param session
     * @param byteBuf
     * @return 
     */
    public static ByteBuf writeUTF8(final CharSequence str, final WriteSessionWithByteBuf session,
            final ByteBuf byteBuf)
    {
        //System.out.println("writeUTF8 413 -> "+str);
        final int len = str.length();
        if (len == 0)     return byteBuf;

        int offset = byteBuf.writerIndex();
        byteBuf.writeCharSequence(str, CharsetUtil.UTF_8);
        session.size += byteBuf.writerIndex()-offset;
        return byteBuf;
    }

    /**
     * Writes the ascii bytes from the string into the {@link ByteBuf}.It is the responsibility of the caller to
 know in advance that the string is 100% ascii.E.g if you convert a double/float to a string, you are sure it
 only contains ascii chars.
     * @param str
     * @param session
     * @param byteBuf
     * @return 
     */
    public static ByteBuf writeAscii(final CharSequence str, final WriteSessionWithByteBuf session,
            ByteBuf byteBuf)
    {
        //System.out.println("writeAscii 437 -> "+str);
        final int len = str.length();
        if (len == 0)
            return byteBuf;

               // actual size
        byteBuf.writeCharSequence(str, CharsetUtil.US_ASCII);
        session.size += len;
        return byteBuf;
    }

//    static void writeFixed2ByteInt(final int value, final byte[] buffer, int offset,
//            final boolean littleEndian)
//    {
//        if (littleEndian)
//        {
//            buffer[offset++] = (byte) value;
//            buffer[offset] = (byte) ((value >>> 8) & 0xFF);
//        }
//        else
//        {
//            buffer[offset++] = (byte) ((value >>> 8) & 0xFF);
//            buffer[offset] = (byte) value;
//        }
//    }
    
    static void writeFixed2ByteInt(final int value, final ByteBuf byteBuf,
            final boolean littleEndian)
    {
        if (littleEndian)
        {
            byteBuf.writeByte((byte) value);
            byteBuf.writeByte((byte) ((value >>> 8) & 0xFF));
        }
        else
        {
            byteBuf.writeByte((byte) ((value >>> 8) & 0xFF));
            byteBuf.writeByte((byte) value);
        }
    }
    
    static void writeFixed2ByteInt(final int value, final ByteBuf byteBuf,int offset,
            final boolean littleEndian)
    {
        if (littleEndian)
        {
            byteBuf.setByte(offset,(byte) value);
            byteBuf.setByte(offset,(byte) ((value >>> 8) & 0xFF));
        }
        else
        {
            byteBuf.setByte(offset,(byte) ((value >>> 8) & 0xFF));
            byteBuf.setByte(offset,(byte) value);
        }
    }

    /**
     * The length of the utf8 bytes is written first (big endian) before the string - which is fixed 2-bytes.Same
 behavior as {@link java.io.DataOutputStream#writeUTF(String)}.
     * @param str
     * @param session
     * @param byteBuf
     * @return 
     */
    public static ByteBuf writeUTF8FixedDelimited(final CharSequence str,
            final WriteSessionWithByteBuf session, ByteBuf byteBuf)
    {
        return writeUTF8FixedDelimited(str, false, session, byteBuf);
    }

    /**
     * The length of the utf8 bytes is written first before the string - which is fixed 2-bytes.
     * @param str
     * @param littleEndian
     * @param session
     * @param byteBuf
     * @return 
     */
    public static ByteBuf writeUTF8FixedDelimited(final CharSequence str,
            final boolean littleEndian, final WriteSessionWithByteBuf session, ByteBuf byteBuf)
    {
        //System.out.println("writeUTF8 512 -> "+str);
        final int lastSize = session.size, len = str.length(), withIntOffset = byteBuf.writerIndex();

        

        if (len == 0)
        {
            writeFixed2ByteInt(0, byteBuf,  littleEndian);
            // update size
            session.size += 2;
            return byteBuf;
        }

       

        // everything fits
       writeFixed2ByteInt(0, byteBuf,  littleEndian);
            // update size
            
        final ByteBuf rb = writeUTF8(str, 0, len, session, byteBuf);
        session.size += 2;
        // update final size
        writeFixed2ByteInt((session.size - lastSize), byteBuf,
                withIntOffset, littleEndian);

        return rb;
    }

    private static ByteBuf writeUTF8OneByteDelimited(final CharSequence str, final int index,
            final int len, final WriteSessionWithByteBuf session, ByteBuf byteBuf)
    {
        final int lastSize = session.size;

        
        // everything fits
        int withIntOffset = byteBuf.writerIndex();
        byteBuf.writeByte(0);
         // update size
        
        final ByteBuf rb = writeUTF8(str, index, len, session, byteBuf);

        // update final size
        byteBuf.setByte(withIntOffset,(byte) (session.size - lastSize));
        session.size++;
        return rb;
    }

    private static ByteBuf writeUTF8VarDelimited(final CharSequence str, final int index,
            final int len, final int lowerLimit, int expectedSize,
            final WriteSessionWithByteBuf session, ByteBuf byteBuf)
    {
               
        byte[] data = str.toString().getBytes(CharsetUtil.UTF_8);
        // everything fits

       int size = data.length;

        if (size < lowerLimit)
        {
            expectedSize--;
        }

        // update size
        session.size += expectedSize;

        for (; --expectedSize > 0; size >>>= 7)
            byteBuf.writeByte((byte) ((size & 0x7F) | 0x80));

       byteBuf.writeByte( (byte) (size));
       byteBuf.writeBytes(data);
       return byteBuf;
    }

    /**
     * The length of the utf8 bytes is written first before the string - which is a variable int (1 to 5 bytes).
     * @param str
     * @param session
     * @param byteBuf
     * @return 
     */
    public static ByteBuf writeUTF8VarDelimited(final CharSequence str, final WriteSessionWithByteBuf session,
            ByteBuf byteBuf)
    {
        final int len = str.length();
        if (len == 0)
        {
            // write zero
            byteBuf.writeByte(0);
            // update size
            session.size++;
            return byteBuf;
        }

        if (len < ONE_BYTE_EXCLUSIVE)
        {
            // the varint will be max 1-byte. (even if all chars are non-ascii)
            return writeUTF8OneByteDelimited(str, 0, len, session, byteBuf);
        }

        if (len < TWO_BYTE_EXCLUSIVE)
        {
            // the varint will be max 2-bytes and could be 1-byte. (even if all non-ascii)
            return writeUTF8VarDelimited(str, 0, len, TWO_BYTE_LOWER_LIMIT, 2,
                    session, byteBuf);
        }

        if (len < THREE_BYTE_EXCLUSIVE)
        {
            // the varint will be max 3-bytes and could be 2-bytes. (even if all non-ascii)
            return writeUTF8VarDelimited(str, 0, len, THREE_BYTE_LOWER_LIMIT, 3,
                    session, byteBuf);
        }

        if (len < FOUR_BYTE_EXCLUSIVE)
        {
            // the varint will be max 4-bytes and could be 3-bytes. (even if all non-ascii)
            return writeUTF8VarDelimited(str, 0, len, FOUR_BYTE_LOWER_LIMIT, 4,
                    session, byteBuf);
        }

        // the varint will be max 5-bytes and could be 4-bytes. (even if all non-ascii)
        return writeUTF8VarDelimited(str, 0, len, FIVE_BYTE_LOWER_LIMIT, 5, session, byteBuf);
    }

    public static final class STRING
    {
        static final boolean CESU8_COMPAT = Boolean.getBoolean("io.protostuff.cesu8_compat");

        private STRING()
        {
        }

        public static String deser(byte[] nonNullValue)
        {
            return deser(nonNullValue, 0, nonNullValue.length);
        }

        public static String deser(byte[] nonNullValue, int offset, int len)
        {
            final String result;
            try
            {
                // Try to use the built in deserialization first, since we expect
                // that the most likely case is a valid UTF-8 encoded byte array.
                // Additionally, the built in serialization method has one less
                // char[] copy than readUTF.
                //
                // If, however, there are invalid/malformed characters, i.e. 3-byte
                // surrogates / 3-byte surrogate pairs, we should fall back to the
                // readUTF method as it should be able to properly handle 3-byte surrogates
                // (and therefore 6-byte surrogate pairs) in Java 8+.
                //
                // While Protostuff and many other applications, still use 3-byte surrogates
                // / 6-byte surrogate pairs, the standard 'forbids' their use, and Java 8
                // has started to enforce the standard, resulting in 'corrupted' data in
                // strings when decoding using new String(nonNullValue, "UTF-8");
                //
                // While the readUTF should be able to handle both Standard Unicode
                // (i.e. new String().getBytes("UTF-8") and the Legacy Unicode
                // (with 3-byte surrogates, used in CESU-8 and Modified UTF-8),
                // we don't want to introduce an unexpected loss of data due to
                // some unforseen bug. As a result, a falbyteBufack mechanism is in
                // place such that the worst case scenario results in the previous
                // implementation's behaviour.
                //
                // For the Java 8 change, see: https://bugs.openjdk.java.net/browse/JDK-7096080

                result = new String(nonNullValue, offset, len, "UTF-8");

                // Check if we should scan the string to make sure there were no
                // corrupt characters caused by 3-byte / 6-byte surrogate pairs.
                //
                // In general, this *should* only be required for systems reading
                // data stored using legacy protostuff. Moving forward, the data
                // should be readable by new String("UTF-8"), so the scan is unnecessary.
                if (CESU8_COMPAT && result.indexOf(0xfffd) != -1)
                {
                    // If it contains the REPLACEMENT character, then there's a strong
                    // possibility of it containing 3-byte surrogates / 6-byte surrogate
                    // pairs, and we should try decoding using readUTF to handle it.
                    try
                    {
                        return readUTF(nonNullValue, offset, len);
                    }
                    catch (UTFDataFormatException e)
                    {
                        // Unexpected, but most systems previously using
                        // Protostuff don't expect error to occur from
                        // String deserialization, so we use this just in case.
                        return result;
                    }
                }
            }
            catch (UnsupportedEncodingException e)
            {
                throw new RuntimeException(e);
            }

            return result;
        }

        /**
         * Deserialize using readUTF only.
         *
         * @param nonNullValue
         * @return
         */
        static String deserCustomOnly(byte[] nonNullValue)
        {
            try
            {
                // Same behaviour as deser(), but does NOT
                // fall back to old implementation.
                return readUTF(nonNullValue, 0, nonNullValue.length);
            }
            catch (UTFDataFormatException e)
            {
                throw new RuntimeException(e);
            }
        }

        public static byte[] ser(String nonNullValue)
        {
            try
            {
                return nonNullValue.getBytes("UTF-8");
            }
            catch (UnsupportedEncodingException e)
            {
                throw new RuntimeException(e);
            }
        }

        /**
         * Reads the string from a byte[] using that was encoded a using Modified UTF-8 format. Additionally supports
         * 4-byte surrogates, de-serializing them as surrogate pairs.
         *
         * See: http://en.wikipedia.org/wiki/UTF-8#Description for encoding details.
         */
        private static String readUTF(byte[] buffer, int offset, int len) throws UTFDataFormatException
        {
            char[] charArray = new char[len];

            int i = 0;
            int c = 0;

            // Optimizaiton: Assume that the characters are all 7-bits encodable
            // (which is most likely the standard case).
            // If they're not, break out and take the 'slow' path.
            for (; i < len; i++)
            {
                int ch = (int) buffer[offset + i] & 0xff;

                // If it's not 7-bit character, break out
                if (ch > 127)
                    break;

                charArray[c++] = (char) ch;
            }

            // 'Slow' path
            while (i < len)
            {
                int ch = (int) buffer[offset + i] & 0xff;

                // Determine how to decode based on 'bits of code point'
                // See: http://en.wikipedia.org/wiki/UTF-8#Description
                int upperBits = ch >> 4;

                if (upperBits <= 7)
                {
                    // 1-byte: 0xxxxxxx
                    charArray[c++] = (char) ch;
                    i++;
                }
                else if (upperBits == 0x0C || upperBits == 0x0D)
                {
                    // 2-byte: 110xxxxx 10xxxxxx
                    i += 2;

                    if (i > len)
                        throw new UTFDataFormatException("Malformed input: Partial character at end");

                    int ch2 = (int) buffer[offset + i - 1];

                    // Make sure the second byte has the form 10xxxxxx
                    if ((ch2 & 0xC0) != 0x80)
                        throw new UTFDataFormatException("Malformed input around byte " + i);

                    charArray[c++] = (char) (((ch & 0x1F) << 6) | (ch2 & 0x3F));
                }
                else if (upperBits == 0xE)
                {
                    // 3-byte: 1110xxxx 10xxxxxx 10xxxxxx
                    i += 3;

                    if (i > len)
                        throw new UTFDataFormatException("Malformed input: Partial character at end");

                    int ch2 = (int) buffer[offset + i - 2];
                    int ch3 = (int) buffer[offset + i - 1];

                    // Check the 10xxxxxx 10xxxxxx of second two bytes
                    if (((ch2 & 0xC0) != 0x80) || ((ch3 & 0xC0) != 0x80))
                        throw new UTFDataFormatException("Malformed input around byte " + (i - 1));

                    charArray[c++] = (char) (((ch & 0x0F) << 12) | ((ch2 & 0x3F) << 6) | (ch3 & 0x3F));
                }
                else
                {
                    // 4-byte: 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
                    upperBits = ch >> 3;
                    if (upperBits == 0x1E)
                    {
                        // Because we're now in the UTF-32 bit range, we must
                        // break it down into the UTF-16 surrogate pairs for
                        // Java's String class (which is UTF-16).

                        i += 4;
                        if (i > len)
                            throw new UTFDataFormatException("Malformed input: Partial character at end");

                        int ch2 = (int) buffer[offset + i - 3];
                        int ch3 = (int) buffer[offset + i - 2];
                        int ch4 = (int) buffer[offset + i - 1];

                        int value =
                                ((ch & 0x07) << 18) |
                                        ((ch2 & 0x3F) << 12) |
                                        ((ch3 & 0x3F) << 6) |
                                        ((ch4 & 0x3F));

                        charArray[c++] = highSurrogate(value);
                        charArray[c++] = lowSurrogate(value);
                    }
                    else
                    {
                        // Anything above
                        throw new UTFDataFormatException("Malformed input at byte " + i);
                    }
                }
            }

            return new String(charArray, 0, c);
        }
    }


    public static char highSurrogate(int codePoint) {
        return (char) ((codePoint >>> 10)
                + (MIN_HIGH_SURROGATE - (MIN_SUPPLEMENTARY_CODE_POINT >>> 10)));
    }

    public static char lowSurrogate(int codePoint) {
        return (char) ((codePoint & 0x3ff) + MIN_LOW_SURROGATE);
    }

}
