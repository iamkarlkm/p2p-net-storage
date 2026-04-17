package com.q3lives.hashmap;

import java.io.Serializable;
import java.util.Date;

/**
 * 测试64位key的封装类
 *
 * @param 键对象
 * @example new TestKey64( Object key)
 */
public class TestKey64 implements Serializable, HashCode64 {

    private static final long serialVersionUID = 1L;
    public final Object key;

    public TestKey64(Object key) {
        this.key = key;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((key == null) ? 0 : key.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof TestKey64)) {
            return false;
        }
        TestKey64 other = (TestKey64) obj;
        if (key == null) {
            if (other.key != null) {
                return false;
            }
        } else if (!key.equals(other.key)) {
            return false;
        }
        return true;
    }

    @Override
    public long hashCode64() {
        if (key instanceof HashCode64) {
            //System.out.println("HashCode64");
            return ((HashCode64) key).hashCode64();

        }
        long hashcode64 = 0;
        if (key instanceof String) {
            //System.out.println("String");
            byte[] bytes = ((String) key).getBytes();
            long tmp1 = bytes[0];
            hashcode64 |= (tmp1 << 56);
            tmp1 = bytes[1];
            hashcode64 |= (tmp1 << 48);
            tmp1 = bytes[2];
            hashcode64 |= (tmp1 << 40);
            tmp1 = bytes[3];
            hashcode64 |= (tmp1 << 32);
            hashcode64 |= (key.hashCode() & 0x00000000ffffffffL);
        } else if (key instanceof Date) {
            hashcode64 = ((Date) key).getTime();
        } else if (key instanceof Long) {
            //System.out.println("Long");
            hashcode64 = ((Long) key).longValue();
        } else {
            //System.out.println("Object");
            long tmp1 = key.hashCode();
            hashcode64 |= (tmp1 << 32);
            hashcode64 |= ((key.toString().hashCode()) & 0x00000000ffffffffL);
        }
        return hashcode64;
        //return hashCode64(key);
    }

    public long hashCode64(String key) {
        System.out.println("String");
        long hashcode64 = 0;

        byte[] bytes = key.getBytes();
        long tmp1 = bytes[0];
        hashcode64 |= (tmp1 << 56);
        tmp1 = bytes[1];
        hashcode64 |= (tmp1 << 48);
        tmp1 = bytes[2];
        hashcode64 |= (tmp1 << 40);
        tmp1 = bytes[3];
        hashcode64 |= (tmp1 << 32);

        hashcode64 |= (key.hashCode() & 0x00000000ffffffffL);
        return hashcode64;
    }

    public long hashCode64(Date key) {
        System.out.println("Date");
        return key.getTime();
    }

    public long hashCode64(long key) {
        System.out.println("long");
        return key;
    }

    public long hashCode64(Long key) {
        System.out.println("Long");
        return key;
    }

    public long hashCode64(Object key) {
        System.out.println("Object");
        long hashcode64 = 0;
        long tmp1 = key.hashCode();
        hashcode64 |= (tmp1 << 32);
        hashcode64 |= ((key.toString().hashCode()) & 0x00000000ffffffffL);

        return hashcode64;

    }
    //测试样例：

    public static void main(String[] args) {
//		String as = "1234569999";
//		byte[] bytes = as.getBytes();
//		long tmp1 = bytes[0];
//		String a = Long.toHexString(new TestKey64(as).hashCode64());
//		String a2 = Long.toHexString((0x090a0b0c0dL & 0x0000000fffffffffL));
//		System.out.println(a);
//		System.out.println(new TestKey64(new Date()).hashCode64());
//		System.out.println(new Date().getTime());
        long s = 90L;
        TestKey64 key = new TestKey64(new Date());
        System.out.println(key.hashCode64());

    }

}
