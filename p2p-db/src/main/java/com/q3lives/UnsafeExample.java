package com.q3lives;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

public class UnsafeExample {

    public static void main(String[] args) {
        int num1 = 12; // 二进制表示: 1100
        int leadingZeros1 = Integer.numberOfLeadingZeros(num1);
        int highestOneBitPos1 = 31 - leadingZeros1;
        System.out.println("最高位为 1 的位置 (int): " + highestOneBitPos1);

        long num2 = 0b101000L;
//        public static void parallelSetAll(int[] array, IntUnaryOperator generator) {
//            Objects.requireNonNull(generator);
//            IntStream.range(0, array.length).parallel().forEach(i -> { array[i] = generator.applyAsInt(i); });
//        }
        int leadingZeros2 = Long.numberOfLeadingZeros(num2);
        int highestOneBitPos2 = 63 - leadingZeros2;
        System.out.println("最高位为 1 的位置 (long): " + highestOneBitPos2);
    }
    public static void main1(String[] args) throws Exception {
        // 获取Unsafe实例
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);

        // 分配一块堆外内存
        long size = 1024;
        long memoryAddress = unsafe.allocateMemory(size);

        // 向堆外内存写入数据
        unsafe.putInt(memoryAddress, 123);
        unsafe.putDouble(memoryAddress + 4, 3.14);

        // 从堆外内存读取数据
        int intValue = unsafe.getInt(memoryAddress);
        double doubleValue = unsafe.getDouble(memoryAddress + 4);

        System.out.println("Read int: " + intValue);
        System.out.println("Read double: " + doubleValue);

        // 释放堆外内存
        unsafe.freeMemory(memoryAddress);
    }
}
