

package io.protostuff;

import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.io.OutputStream;
/**
 * B64CodeWithByteBuf。
 */


public final class B64CodeWithByteBuf
{
    // ------------------------------------------------------------------
    static final byte pad = (byte) '=';
    static final byte[] nibble2code =
    {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P',
            'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f',
            'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v',
            'w', 'x', 'y', 'z', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '/'
    };

    static final byte[] code2nibble;

    static
    {
        code2nibble = new byte[256];
        for (int i = 0; i < 256; i++)
            code2nibble[i] = -1;
        for (byte b = 0; b < 64; b++)
            code2nibble[nibble2code[b]] = b;
        code2nibble[pad] = 0;
    }

    private B64CodeWithByteBuf()
    {
    }

    /**
     * Fast Base 64 encode as described in RFC 1421.
     */
    public static byte[] encode(byte[] input)
    {
        return encode(input, 0, input.length);
    }

    /**
     * Fast Base 64 encode as described in RFC 1421.
     */
    public static byte[] encode(byte[] input, int inOffset, int inLen)
    {
        final byte[] output = new byte[((inLen + 2) / 3) * 4];
        encode(input, inOffset, inLen, output, 0);
        return output;
    }

    /**
     * Fast Base 64 encode as described in RFC 1421.
     */
    public static char[] cencode(byte[] input)
    {
        return cencode(input, 0, input.length);
    }

    /**
     * Fast Base 64 encode as described in RFC 1421.
     */
    public static char[] cencode(byte[] input, int inOffset, int inLen)
    {
        final char[] output = new char[((inLen + 2) / 3) * 4];
        cencode(input, inOffset, inLen, output, 0);
        return output;
    }

    // ------------------------------------------------------------------

    /**
     * Fast Base 64 encode as described in RFC 1421.
     * <p>
     * Does not insert whitespace as described in RFC 1521.
     * <p>
     * Avoids creating extra copies of the input/output.
     */
    private static void encode(final byte[] input, int inOffset, final int inLen,
            final byte[] output, int outOffset)
    {
        byte b0, b1, b2;
        final int remaining = inLen % 3, stop = inOffset + (inLen - remaining);
        while (inOffset < stop)
        {
            b0 = input[inOffset++];
            b1 = input[inOffset++];
            b2 = input[inOffset++];
            output[outOffset++] = nibble2code[(b0 >>> 2) & 0x3f];
            output[outOffset++] = nibble2code[(b0 << 4) & 0x3f | (b1 >>> 4) & 0x0f];
            output[outOffset++] = nibble2code[(b1 << 2) & 0x3f | (b2 >>> 6) & 0x03];
            output[outOffset++] = nibble2code[b2 & 077];
        }

        switch (remaining)
        {
            case 0:
                break;
            case 1:
                b0 = input[inOffset++];
                output[outOffset++] = nibble2code[(b0 >>> 2) & 0x3f];
                output[outOffset++] = nibble2code[(b0 << 4) & 0x3f];
                output[outOffset++] = pad;
                output[outOffset++] = pad;
                break;
            case 2:
                b0 = input[inOffset++];
                b1 = input[inOffset++];
                output[outOffset++] = nibble2code[(b0 >>> 2) & 0x3f];
                output[outOffset++] = nibble2code[(b0 << 4) & 0x3f | (b1 >>> 4) & 0x0f];
                output[outOffset++] = nibble2code[(b1 << 2) & 0x3f];
                output[outOffset++] = pad;
                break;

            default:
                throw new IllegalStateException("should not happen");
        }
    }

    // ------------------------------------------------------------------

    /**
     * Fast Base 64 encode as described in RFC 1421.
     * <p>
     * Does not insert whitespace as described in RFC 1521.
     * <p>
     * Avoids creating extra copies of the input/output.
     */
    private static void cencode(final byte[] input, int inOffset, final int inLen,
            final char[] output, int outOffset)
    {
        byte b0, b1, b2;
        final int remaining = inLen % 3, stop = inOffset + (inLen - remaining);
        while (inOffset < stop)
        {
            b0 = input[inOffset++];
            b1 = input[inOffset++];
            b2 = input[inOffset++];
            output[outOffset++] = (char) nibble2code[(b0 >>> 2) & 0x3f];
            output[outOffset++] = (char) nibble2code[(b0 << 4) & 0x3f | (b1 >>> 4) & 0x0f];
            output[outOffset++] = (char) nibble2code[(b1 << 2) & 0x3f | (b2 >>> 6) & 0x03];
            output[outOffset++] = (char) nibble2code[b2 & 077];
        }

        switch (remaining)
        {
            case 0:
                break;
            case 1:
                b0 = input[inOffset++];
                output[outOffset++] = (char) nibble2code[(b0 >>> 2) & 0x3f];
                output[outOffset++] = (char) nibble2code[(b0 << 4) & 0x3f];
                output[outOffset++] = pad;
                output[outOffset++] = pad;
                break;
            case 2:
                b0 = input[inOffset++];
                b1 = input[inOffset++];
                output[outOffset++] = (char) nibble2code[(b0 >>> 2) & 0x3f];
                output[outOffset++] = (char) nibble2code[(b0 << 4) & 0x3f | (b1 >>> 4) & 0x0f];
                output[outOffset++] = (char) nibble2code[(b1 << 2) & 0x3f];
                output[outOffset++] = pad;
                break;

            default:
                throw new IllegalStateException("should not happen");
        }
    }

    /*
     * private static int encodeExplicit(final byte[] input, int inOffset, final int inLen, final byte[] output, int
     * outOffset, int loops) { for (byte b0, b1, b2; loops-->0;) { b0=input[inOffset++]; b1=input[inOffset++];
     * b2=input[inOffset++]; output[outOffset++]=nibble2code[(b0>>>2)&0x3f];
     * output[outOffset++]=nibble2code[(b0<<4)&0x3f|(b1>>>4)&0x0f];
     * output[outOffset++]=nibble2code[(b1<<2)&0x3f|(b2>>>6)&0x03]; output[outOffset++]=nibble2code[b2&077]; }
     * 
     * return inOffset; }
     */

    /**
     * Encodes the byte array into the {@link ByteBuf} and grows when full.
     * @param input
     * @param inOffset
     * @param inLen
     * @param session
     * @param byteBuf
     * @return 
     * @throws java.io.IOException 
     */
    public static ByteBuf encode(final byte[] input, int inOffset, int inLen,
            final WriteSessionWithByteBuf session, final ByteBuf byteBuf) throws IOException
    {
        int outputSize = ((inLen + 2) / 3) * 4;
        session.size += outputSize;
        final byte[] encoded = new byte[outputSize];
                    encode(input, inOffset, inLen, encoded, 0);
           byteBuf.writeBytes(encoded);
        

        return byteBuf;
    }

    /**
     * Encodes the byte array into the {@link ByteBuf} and flushes to the {@link OutputStream} when buffer is full.
     * @param input
     * @param inOffset
     * @param inLen
     * @param session
     * @param byteBuf
     * @return 
     * @throws java.io.IOException 
     */
    public static ByteBuf sencode(final byte[] input, int inOffset, int inLen,
            final WriteSessionWithByteBuf session,
            final ByteBuf byteBuf) throws IOException
    {
        int outputSize = ((inLen + 2) / 3) * 4;
        session.size += outputSize;

        final byte[] encoded = new byte[outputSize];
                    encode(input, inOffset, inLen, encoded, 0);
           byteBuf.writeBytes(encoded);
        

        return byteBuf;
    }

    /**
     * Fast Base 64 decode as described in RFC 1421.
     */
    public static byte[] decode(final byte[] b)
    {
        return decode(b, 0, b.length);
    }

    /**
     * Fast Base 64 decode as described in RFC 1421.
     */
    public static byte[] cdecode(final char[] b)
    {
        return cdecode(b, 0, b.length);
    }

    /* ------------------------------------------------------------ */

    /**
     * Fast Base 64 decode as described in RFC 1421.
     * <p>
     * Does not attempt to cope with extra whitespace as described in RFC 1521.
     * <p>
     * Avoids creating extra copies of the input/output.
     * <p>
     * Note this code has been flattened for performance.
     * 
     * @param input
     *            byte array to decode.
     * @param inOffset
     *            the offset.
     * @param inLen
     *            the length.
     * @return byte array containing the decoded form of the input.
     * @throws IllegalArgumentException
     *             if the input is not a valid B64 encoding.
     */
    public static byte[] decode(final byte[] input, int inOffset, final int inLen)
    {
        if (inLen == 0)
            return ByteString.EMPTY_BYTE_ARRAY;

        if (inLen % 4 != 0)
            throw new IllegalArgumentException("Input block size is not 4");

        int withoutPaddingLen = inLen, limit = inOffset + inLen;
        while (input[--limit] == pad)
            withoutPaddingLen--;

        // Create result array of exact required size.
        final int outLen = ((withoutPaddingLen) * 3) / 4;
        final byte[] output = new byte[outLen];

        decode(input, inOffset, inLen, output, 0, outLen);

        return output;
    }

    /**
     * Fast Base 64 decode as described in RFC 1421.
     * <p>
     * Does not attempt to cope with extra whitespace as described in RFC 1521.
     * <p>
     * Avoids creating extra copies of the input/output.
     * <p>
     * Note this code has been flattened for performance.
     * 
     * @param input
     *            char array to decode.
     * @param inOffset
     *            the offset.
     * @param inLen
     *            the length.
     * @return byte array containing the decoded form of the input.
     * @throws IllegalArgumentException
     *             if the input is not a valid B64 encoding.
     */
    public static byte[] cdecode(final char[] input, int inOffset, final int inLen)
    {
        if (inLen == 0)
            return ByteString.EMPTY_BYTE_ARRAY;

        if (inLen % 4 != 0)
            throw new IllegalArgumentException("Input block size is not 4");

        int withoutPaddingLen = inLen, limit = inOffset + inLen;
        while (input[--limit] == pad)
            withoutPaddingLen--;

        // Create result array of exact required size.
        final int outLen = ((withoutPaddingLen) * 3) / 4;
        final byte[] output = new byte[outLen];

        cdecode(input, inOffset, inLen, output, 0, outLen);

        return output;
    }

    /**
     * Returns the length of the decoded base64 input (written to the provided {@code output} byte array). The
     * {@code output} byte array must have enough capacity or it will fail.
     */
    public static int decodeTo(final byte[] output, int outOffset,
            final byte[] input, int inOffset, final int inLen)
    {
        if (inLen == 0)
            return 0;

        if (inLen % 4 != 0)
            throw new IllegalArgumentException("Input block size is not 4");

        int withoutPaddingLen = inLen, limit = inOffset + inLen;
        while (input[--limit] == pad)
            withoutPaddingLen--;

        // Create result array of exact required size.
        final int outLen = ((withoutPaddingLen) * 3) / 4;
        assert (output.length - outOffset) >= outLen;

        decode(input, inOffset, inLen, output, outOffset, outLen);

        return outLen;
    }

    private static void decode(final byte[] input, int inOffset, final int inLen,
            final byte[] output, int outOffset, final int outLen)
    {
        int stop = (outLen / 3) * 3;
        byte b0, b1, b2, b3;
        try
        {
            while (outOffset < stop)
            {
                b0 = code2nibble[input[inOffset++]];
                b1 = code2nibble[input[inOffset++]];
                b2 = code2nibble[input[inOffset++]];
                b3 = code2nibble[input[inOffset++]];
                if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0)
                    throw new IllegalArgumentException("Not B64 encoded");

                output[outOffset++] = (byte) (b0 << 2 | b1 >>> 4);
                output[outOffset++] = (byte) (b1 << 4 | b2 >>> 2);
                output[outOffset++] = (byte) (b2 << 6 | b3);
            }

            if (outLen != outOffset)
            {
                switch (outLen % 3)
                {
                    case 0:
                        break;
                    case 1:
                        b0 = code2nibble[input[inOffset++]];
                        b1 = code2nibble[input[inOffset++]];
                        if (b0 < 0 || b1 < 0)
                            throw new IllegalArgumentException("Not B64 encoded");
                        output[outOffset++] = (byte) (b0 << 2 | b1 >>> 4);
                        break;
                    case 2:
                        b0 = code2nibble[input[inOffset++]];
                        b1 = code2nibble[input[inOffset++]];
                        b2 = code2nibble[input[inOffset++]];
                        if (b0 < 0 || b1 < 0 || b2 < 0)
                            throw new IllegalArgumentException("Not B64 encoded");
                        output[outOffset++] = (byte) (b0 << 2 | b1 >>> 4);
                        output[outOffset++] = (byte) (b1 << 4 | b2 >>> 2);
                        break;

                    default:
                        throw new IllegalStateException("should not happen");
                }
            }
        }
        catch (IndexOutOfBoundsException e)
        {
            throw new IllegalArgumentException("char " + inOffset
                    + " was not B64 encoded");
        }
    }

    private static void cdecode(final char[] input, int inOffset, final int inLen,
            final byte[] output, int outOffset, final int outLen)
    {
        int stop = (outLen / 3) * 3;
        byte b0, b1, b2, b3;
        try
        {
            while (outOffset < stop)
            {
                b0 = code2nibble[input[inOffset++]];
                b1 = code2nibble[input[inOffset++]];
                b2 = code2nibble[input[inOffset++]];
                b3 = code2nibble[input[inOffset++]];
                if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0)
                    throw new IllegalArgumentException("Not B64 encoded");

                output[outOffset++] = (byte) (b0 << 2 | b1 >>> 4);
                output[outOffset++] = (byte) (b1 << 4 | b2 >>> 2);
                output[outOffset++] = (byte) (b2 << 6 | b3);
            }

            if (outLen != outOffset)
            {
                switch (outLen % 3)
                {
                    case 0:
                        break;
                    case 1:
                        b0 = code2nibble[input[inOffset++]];
                        b1 = code2nibble[input[inOffset++]];
                        if (b0 < 0 || b1 < 0)
                            throw new IllegalArgumentException("Not B64 encoded");
                        output[outOffset++] = (byte) (b0 << 2 | b1 >>> 4);
                        break;
                    case 2:
                        b0 = code2nibble[input[inOffset++]];
                        b1 = code2nibble[input[inOffset++]];
                        b2 = code2nibble[input[inOffset++]];
                        if (b0 < 0 || b1 < 0 || b2 < 0)
                            throw new IllegalArgumentException("Not B64 encoded");
                        output[outOffset++] = (byte) (b0 << 2 | b1 >>> 4);
                        output[outOffset++] = (byte) (b1 << 4 | b2 >>> 2);
                        break;

                    default:
                        throw new IllegalStateException("should not happen");
                }
            }
        }
        catch (IndexOutOfBoundsException e)
        {
            throw new IllegalArgumentException("char " + inOffset
                    + " was not B64 encoded");
        }
    }

    /**
     * Returns the base 64 decoded bytes.The provided {@code str} must already be base-64 encoded.
     * @param str
     * @return 
     */
    public static byte[] decode(final String str)
    {
        return decode(str, 0, str.length());
    }

    /**
     * Returns the base 64 decoded bytes.The provided {@code str} must already be base-64 encoded.
     * @param str
     * @param inOffset
     * @param inLen
     * @return 
     */
    public static byte[] decode(final String str, int inOffset, final int inLen)
    {
        if (inLen == 0)
            return new byte[0];

        if (inLen % 4 != 0)
            throw new IllegalArgumentException("Input block size is not 4");

        int withoutPaddingLen = inLen, limit = inOffset + inLen;
        while (str.charAt(--limit) == pad)
            withoutPaddingLen--;

        // Create result array of exact required size.
        final int outLen = ((withoutPaddingLen) * 3) / 4;
        final byte[] output = new byte[outLen];

        decode(str, inOffset, inLen, output, 0, outLen);

        return output;
    }

    /**
     * Returns the length of the decoded base64 input (written to the provided {@code output} byte array).The
    {@code output} byte array must have enough capacity or it will fail.
     * @param output
     * @param outOffset
     * @param str
     * @param inOffset
     * @param inLen
     * @return 
     */
    public static int decodeTo(final byte[] output, int outOffset,
            final String str, int inOffset, final int inLen)
    {
        if (inLen == 0)
            return 0;

        if (inLen % 4 != 0)
            throw new IllegalArgumentException("Input block size is not 4");

        int withoutPaddingLen = inLen, limit = inOffset + inLen;
        while (str.charAt(--limit) == pad)
            withoutPaddingLen--;

        // Create result array of exact required size.
        final int outLen = ((withoutPaddingLen) * 3) / 4;
        assert (output.length - outOffset) >= outLen;

        decode(str, inOffset, inLen, output, outOffset, outLen);

        return outLen;
    }

    private static void decode(final String str, int inOffset, final int inLen,
            final byte[] output, int outOffset, final int outLen)
    {
        int stop = (outLen / 3) * 3;
        byte b0, b1, b2, b3;
        try
        {
            while (outOffset < stop)
            {
                b0 = code2nibble[str.charAt(inOffset++)];
                b1 = code2nibble[str.charAt(inOffset++)];
                b2 = code2nibble[str.charAt(inOffset++)];
                b3 = code2nibble[str.charAt(inOffset++)];
                if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0)
                    throw new IllegalArgumentException("Not B64 encoded");

                output[outOffset++] = (byte) (b0 << 2 | b1 >>> 4);
                output[outOffset++] = (byte) (b1 << 4 | b2 >>> 2);
                output[outOffset++] = (byte) (b2 << 6 | b3);
            }

            if (outLen != outOffset)
            {
                switch (outLen % 3)
                {
                    case 0:
                        break;
                    case 1:
                        b0 = code2nibble[str.charAt(inOffset++)];
                        b1 = code2nibble[str.charAt(inOffset++)];
                        if (b0 < 0 || b1 < 0)
                            throw new IllegalArgumentException("Not B64 encoded");
                        output[outOffset++] = (byte) (b0 << 2 | b1 >>> 4);
                        break;
                    case 2:
                        b0 = code2nibble[str.charAt(inOffset++)];
                        b1 = code2nibble[str.charAt(inOffset++)];
                        b2 = code2nibble[str.charAt(inOffset++)];
                        if (b0 < 0 || b1 < 0 || b2 < 0)
                            throw new IllegalArgumentException("Not B64 encoded");
                        output[outOffset++] = (byte) (b0 << 2 | b1 >>> 4);
                        output[outOffset++] = (byte) (b1 << 4 | b2 >>> 2);
                        break;

                    default:
                        throw new IllegalStateException("should not happen");
                }
            }
        }
        catch (IndexOutOfBoundsException e)
        {
            throw new IllegalArgumentException("char " + inOffset
                    + " was not B64 encoded");
        }
    }

}
