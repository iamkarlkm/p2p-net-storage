package com.q3lives.ds.util;

import net.jpountz.xxhash.StreamingXXHash32;
import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHashFactory;

import java.nio.charset.Charset;
import java.util.Random;

/**
 * 数据工具类。
 * <p>
 * 提供常用的数据操作方法，包括：
 * <ul>
 *     <li><b>哈希计算：</b> 基于 xxHash 算法的 32位和 64位哈希计算 ({@link #hash32(byte[])}, {@link #hash64(byte[])})。</li>
 *     <li><b>位操作：</b> 针对 int 数组的位设置、清除和测试操作 ({@link #setBit(int[], int)}, {@link #clearBit(int[], int)}, {@link #testBit(int[], int)})。</li>
 *     <li><b>基本类型序列化：</b> long/int 与 byte 数组的相互转换。</li>
 *     <li><b>映射计算：</b> 计算哈希值在位图中的索引和位置。</li>
 * </ul>
 * </p>
 */
public class DsDataUtil {

    private static final ThreadLocal<StreamingXXHash32> LOCAL_XXHASH32 = new ThreadLocal<StreamingXXHash32>();
    private static final ThreadLocal<StreamingXXHash64> LOCAL_XXHASH64 = new ThreadLocal<StreamingXXHash64>();
    private static final int LOCAL_XXHASH32_SEED = 0x9747b28c;
    private static final long LOCAL_XXHASH64_SEED = 0x9747b28c9747b28cl;

    protected static boolean DEFAULT_COUNT = true;

    /**
     * 计算字节数组的 32位 xxHash 值。
     * @param data 数据
     * @return 哈希值
     */
    public final static int hash32(byte[] data) {
        StreamingXXHash32 hash = LOCAL_XXHASH32.get();
        if (null == hash) {
            XXHashFactory factory = XXHashFactory.fastestInstance();
            hash = factory.newStreamingHash32(LOCAL_XXHASH32_SEED);
            LOCAL_XXHASH32.set(hash);
        }
        int hashCode = 0;
        try {
            hash.update(data, 0, data.length);
            hashCode = hash.getValue();
        } finally {
            hash.reset();
        }
        return hashCode;
    }

    /**
     * 计算字节数组的 64位 xxHash 值。
     * @param data 数据
     * @return 哈希值
     */
    public final static long hash64(byte[] data) {
        StreamingXXHash64 hash = LOCAL_XXHASH64.get();
        if (null == hash) {
            XXHashFactory factory = XXHashFactory.fastestInstance();
            hash = factory.newStreamingHash64(LOCAL_XXHASH64_SEED);
            LOCAL_XXHASH64.set(hash);
        }
        long hashCode = 0;
        try {
            hash.update(data, 0, data.length);
            hashCode = hash.getValue();
        } finally {
            hash.reset();
        }
        return hashCode;
    }

    /**
     * 计算字符串的 64位哈希值。
     * @param str 字符串
     * @return 哈希值
     */
    public final static long hash64(String str) {
        StreamingXXHash32 hash = LOCAL_XXHASH32.get();
        if (null == hash) {
            XXHashFactory factory = XXHashFactory.fastestInstance();
            hash = factory.newStreamingHash32(LOCAL_XXHASH32_SEED);
            LOCAL_XXHASH32.set(hash);
        }
        long value = 0;
        try {
            byte[] data = str.getBytes(UTF_8);
            hash.update(data, 0, data.length);
            int hash32 = hash.getValue();
            //System.out.println(Integer.toHexString(hash32));
            //System.out.println(Integer.toHexString(((hash32 >> (1 * 8)) & 0xFF)));
            //低4字节
            for (int i = 0; i < INT_SIZE; i++) {
                value |= ((long) ((byte) (hash32 >> (i * 8)) & 0xFF)) << (i * 8);
            }
//            //高4字节
            int pos = 8;
            int n = Math.min(INT_SIZE, data.length);
            for (int i = 0; i < n; i++) {
                pos--;
                //System.out.println(pos);
                value |= ((long) (data[i] & 0xFF)) << (pos * 8);
                //System.out.println(Long.toHexString(value));
            }

        } finally {
            hash.reset();
        }

        return value;
    }

    /**
     * 计算字节数组的 64位哈希值 (基于 32位哈希扩展)。
     * @param data 数据
     * @return 哈希值
     */
    public final static long hash64ForString(byte[] data) {
        StreamingXXHash32 hash = LOCAL_XXHASH32.get();
        if (null == hash) {
            XXHashFactory factory = XXHashFactory.fastestInstance();
            hash = factory.newStreamingHash32(LOCAL_XXHASH32_SEED);
            LOCAL_XXHASH32.set(hash);
        }
        long value = 0;
        try {
            hash.update(data, 0, data.length);
            int hash32 = hash.getValue();
            //System.out.println(Integer.toHexString(hash32));
            //System.out.println(Integer.toHexString(((hash32 >> (1 * 8)) & 0xFF)));
            //低4字节
            for (int i = 0; i < INT_SIZE; i++) {
                value |= ((long) ((byte) (hash32 >> (i * 8)) & 0xFF)) << (i * 8);
            }
//            //高4字节
            int pos = 8;
            int n = Math.min(INT_SIZE, data.length);
            for (int i = 0; i < n; i++) {
                pos--;
                //System.out.println(pos);
                value |= ((long) (data[i] & 0xFF)) << (pos * 8);
                //System.out.println(Long.toHexString(value));
            }

        } finally {
            hash.reset();
        }

        return value;
    }

    /**
     * 计算 MD5 摘要（16B）。
     */
    public final static byte[] md5(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            return md.digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 计算 SHA-256 摘要（32B）。
     */
    public final static byte[] sha256(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int LONG_SIZE = 8;
    private static final int INT_SIZE = 4;
    private static final int SHORT_SIZE = 4;
    private static final int MD5_SIZE = 16;

    /**
     * 将 long 值存入字节数组。
     * @param bytes
     * @param offset
     * @param value
     */
    public final static void storeLong(byte[] bytes, int offset, long value) {
       
        for (int i = 0,j=LONG_SIZE-1; i < LONG_SIZE; i++,j--) {
            bytes[offset + i] = (byte) (value >> (j * 8));
        }

    }
    
    public static void main(String[] args) {
         byte[] b = new byte[8];
        DsDataUtil.storeLong(b, 0, -10000);
        for(int i= 0;i<8;i++){
            int x = b[i]&0xff;
            System.out.println(Integer.toHexString(x));
        }
    }

    /**
     * 从字节数组加载 long 值。
     */
    public final static long loadLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < LONG_SIZE; i++) {
            value |= ((long) (bytes[offset + i] & 0xFF)) << (i * 8);
        }
        return value;
    }
    
    /**
     * 将 short 值存入字节数组。
     */
    public final static void storeShort(byte[] bytes, int offset, int value) {
        for (int i = 0; i < SHORT_SIZE; i++) {
            bytes[offset + i] = (byte) (value >> (i * 8));
        }
    }

    /**
     * 将 int 值存入字节数组。
     */
    public final static void storeInt(byte[] bytes, int offset, int value) {
        for (int i = 0; i < INT_SIZE; i++) {
            bytes[offset + i] = (byte) (value >> (i * 8));
        }
    }

    /**
     * 从字节数组加载 int 值。
     */
    public final static int loadInt(byte[] bytes, int offset) {
        int value = 0;
        for (int i = 0; i < INT_SIZE; i++) {
            value |= ((bytes[offset + i] & 0xFF)) << (i * 8);
        }
        return value;
    }

    public int index = 0;

  

    /**
     * 自增 dsDataUtil.index（用于 main 演示）。
     */
    public static void indexAdd(DsDataUtil dsDataUtil) {
        dsDataUtil.index++;
    }

    /**
     * 本类的另一段自测入口（演示 hash32/hash64 与 XOR ecc）。
     *
     * <p>仅用于开发调试，不参与系统主流程。</p>
     */
    public final static void main2(String[] args) {
        String str = "12345345234572";
        System.out.println(Integer.toHexString(hash32(str.getBytes(UTF_8))));

        System.out.println(Long.toHexString(hash64(str)));

        int[] data = new int[16];
        int crc = 0;
        for(int i=0;i<data.length;i++){
            data[i] = new Random().nextInt(1000000);
            crc ^= data[i];
           // System.out.println("ecc="+crc);
        }
        System.out.println("result ecc="+crc);
//        for(int i=0;i<data.length;i++){
//            data[i] = i+3;
//            crc ^= i;
//        }
        int a1 = new Random().nextInt(1000000);
        int a2 = new Random().nextInt(1000000);
        int a3 = new Random().nextInt(1000000);
        System.out.println("a1="+a1);
        System.out.println("a2="+a2);
        System.out.println("a3="+a3);

        int ecc1 = a1^a2;
        int ecc2 = a1^a3;
        int ecc3 = a2^a3;
        System.out.println("ecc1^ecc2="+(ecc1^ecc2));
        System.out.println("ecc1^ecc3="+(ecc1^ecc3));
        System.out.println("ecc2^ecc3="+(ecc2^ecc3));

//
//        for(int i=1;i<data.length;i++){
//            System.out.println(data[i]+" -> "+(crc^data[i]));
//            System.out.println(data[i]+" -> "+(crc^data[i-1]));
//        }
//
////        setBit(data, 17);
////        System.out.println(testBit(data, 17));
////
////        clearBit(data, 17);
//        System.out.println(4 * 8 * 4);
//
//        setBit(data, 127);
////        clearBit(data, 1);
//        System.out.println(testBit(data, 127));
//        System.out.println(Long.toHexString(bitsToLong(0xFFFFFFFFFFFFFFFFL, 4, 15)));
//        clearBit(data, 17);
//        System.out.println(testBit(data, 127));

    }

    /**
     * 在 int 数组的指定位置设置位为 1。
     * @param data int 数组
     * @param n 位的索引
     */
    public final static void setBit(int[] data, int n) {
        //System.out.println("(n & 31) -> "+(n & 31));
        if (n < 0) {
            throw new ArithmeticException("位地址不能为负");
        }
        int limit = 4 * 8 * data.length;
        if (n < limit) {
            int intNum = n >>> 5;
            //System.out.println("intNum bits ="+(4*intNum*8+(n & 31)));
            //System.out.println("set intNum="+(data.length-intNum-1));

            //data[data.length-intNum-1] |= (1 << (n & 31));
            data[intNum] |= (1 << (31 - (n & 31)));
        }  else {
            throw new ArrayIndexOutOfBoundsException(String.format("索引 %d 超出长度 %d", n, limit));

        }

    }

    /**
     * 从 long 值中提取指定范围的位。
     */
    public final static long bitsToLong(long value, int start, int count) {
        //System.out.println("(n & 31) -> "+(n & 31));
        long end = start + count-1;
        if (start < 0 || end < 0) {
            throw new ArithmeticException("位地址不能为负");
        }
        int limit = 64;
        long result = 0;
        if (start < limit && end < limit) {
            long mask = (0xFFFFFFFFFFFFFFFFL >>> start) & (0xFFFFFFFFFFFFFFFFL << (63-end));

            result =  value & mask;
            //System.out.println(testBit(Long.valueOf(value>>>31).intValue(), start-1));
            // System.out.println("bitsToLong="+Integer.toHexString(Long.valueOf(value>>>31).intValue()));
//            value &= (0xFFFFFFFFFFFFFFFFL >>> (63-end));
//            value >>>= end;
            //System.out.println(Long.toHexString(value));
        }  else {
            throw new ArrayIndexOutOfBoundsException(String.format("索引 %d,%d 超出长度 %d", start,end, limit));

        }

        return result;
    }

    /**
     * 从 int 值中提取指定范围的位。
     */
    public final static int bitsToInt(int value, int start, int count) {
        //System.out.println("(n & 31) -> "+(n & 31));
        long end = start + count-1;
        if (start < 0 || end < 0) {
            throw new ArithmeticException("位地址不能为负");
        }
        int limit = 32;
        int result = 0;
        if (start < limit && end < limit) {
            int mask = (0xFFFFFFFF >>> start) & (0xFFFFFFFF << (31-end));

            result = value & mask;
            //System.out.println(testBit(Long.valueOf(value>>>31).intValue(), start-1));
            // System.out.println("bitsToLong="+Integer.toHexString(Long.valueOf(value>>>31).intValue()));
//            value &= (0xFFFFFFFFFFFFFFFFL >>> (63-end));
//            value >>>= end;
            //System.out.println(Long.toHexString(value));
        }  else {
            throw new ArrayIndexOutOfBoundsException(String.format("索引 %d,%d 超出长度 %d", start,end, limit));

        }

        return result;
    }

    /**
     * 在 int 数组的指定位置清除位（置为 0）。
     */
    public final static void clearBit(int[] data, int n) {
        if (n < 0) {
            throw new ArithmeticException("位地址不能为负");
        }
        int limit = 4 * 8 * data.length;
        if (n < limit) {
            int intNum = n >>> 5;
            //System.out.println("intNum bits ="+(4*intNum*8+(n & 31)));

            data[intNum] &= ~(1 << (31 - (n & 31)));
        } else {
            throw new ArrayIndexOutOfBoundsException(String.format("索引 %d 超出长度 %d", n, limit));

        }

    }

    /**
     * 测试 int 数组指定位置的位是否为 1。
     */
    public final static boolean testBit(int[] data, int n) {
        if (n < 0) {
            throw new ArithmeticException("位地址不能为负");
        }
        int intNum = n >>> 5;

        int limit = 4 * 8 * data.length;
        if (n < limit) {
//            System.out.println("intNum bits =" + (4 * intNum * 8 + (n & 31)));
            int magInt = data[intNum];
            return (magInt & (1 << (31 - (n & 31)))) != 0;
        } else {
            throw new ArrayIndexOutOfBoundsException(String.format("索引 %d 超出长度 %d", n, limit));

        }
    }

    /**
     * 设置 int 值指定位置的位为 1。
     */
    public final static int setBit(int data, int n) {
        //System.out.println("(n & 31) -> "+(n & 31));
        if (n < 0) {
            throw new ArithmeticException("位地址不能为负");
        }
        int limit = 4 * 8 ;
        if (n < limit) {
            int intNum = n >>> 5;
            data |= (1 << (31 - (n & 31)));
            return data;
        }  else {
            throw new ArrayIndexOutOfBoundsException(String.format("索引 %d 超出长度 %d", n, limit));

        }

    }

    /**
     * 清除 int 值指定位置的位（置为 0）。
     */
    public final static int clearBit(int data, int n) {
        if (n < 0) {
            throw new ArithmeticException("位地址不能为负");
        }
        int limit = 4 * 8;
        if (n < limit) {
            int intNum = n >>> 5;
            //System.out.println("intNum bits ="+(4*intNum*8+(n & 31)));

            data &= ~(1 << (31 - (n & 31)));
            return data;
        } else {
            throw new ArrayIndexOutOfBoundsException(String.format("索引 %d 超出长度 %d", n, limit));

        }

    }

    /**
     * 测试 int 值指定位置的位是否为 1。
     */
    public final static boolean testBit(int data, int n) {
        if (n < 0) {
            throw new ArithmeticException("位地址不能为负");
        }
        int intNum = n >>> 5;

        int limit = 4 * 8;
        if (n < limit) {

            return (data & (1 << (31 - (n & 31)))) != 0;
        }  else {
            throw new ArrayIndexOutOfBoundsException(String.format("索引 %d 超出长度 %d", n, limit));

        }
    }

    /**
     * 计算哈希值在位图中的位置 (int2BitsMapPosition)。
     */
    public final static int int2BitsMapPosition(int hash, int n) {

        // 将hash值乘以2，再加上n，然后求余数%32，得到的结果即为intBits2MapPosition的值
        return (hash*2+n) %32;
    }

    public final static int int2BitsMapPosition(int hash) {

        // 将hash值乘以2，再加上n，然后求余数%32，得到的结果即为intBits2MapPosition的值
        return (hash*2) %32;
    }

    /**
     * 计算 hash 在 32bit 位图（int）的 bit 偏移（0..31）。
     */
    public final static int intBitsMapPosition(int hash) {

        // 将hash值乘以2，再加上n，然后求余数%32，得到的结果即为intBits2MapPosition的值
        return hash %32;
    }

    /**
     * 计算哈希值在位图数组中的索引 (int2BitsMapIndex)。
     */
    public final static int int2BitsMapIndex(int hash, int n) {

        // 将hash值乘以2，再加上n，然后/32，得到的结果即为intBits2MapPosition的值
        return (hash*2+n) >>> 5;
    }

    /**
     * 计算 hash 在位图 int[] 中的数组下标（以 32bit 为一个槽）。
     */
    public final static int intBitsMapIndex(int hash, int n) {

        // 将hash值乘以2，再加上n，然后/32，得到的结果即为intBits2MapPosition的值
        return (hash+n) >>> 5;
    }

    /**
     * 计算 hash*2 在位图 int[] 中的数组下标（以 32bit 为一个槽）。
     */
    public final static int int2BitsMapIndex(int hash) {

        // 将hash值乘以2，再加上n，然后/32，得到的结果即为intBits2MapPosition的值
        return (hash*2) >>> 5;
    }

    /**
     * 计算 hash 在位图 int[] 中的数组下标（以 32bit 为一个槽）。
     */
    public final static int intBitsMapIndex(int hash) {

        // 将hash值乘以2，再加上n，然后/32，得到的结果即为intBits2MapPosition的值
        return (hash) >>> 5;
    }



}
