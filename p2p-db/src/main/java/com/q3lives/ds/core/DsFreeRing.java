
package com.q3lives.ds.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;

public  class DsFreeRing implements AutoCloseable {
        private static final byte[] MAGIC = new byte[] {'.', 'F', '-', 'R'};
        private static final int OFF_MAGIC = 0;
        private static final int OFF_CAP = 4;
        private static final int OFF_HEAD = 8;
        private static final int OFF_TAIL = 12;
        private static final int OFF_COUNT = 16;
        private static final int HEADER_BYTES = 20;
        private static final int OFF_DATA = HEADER_BYTES;

        private final ReentrantLock lock = new ReentrantLock();
        private final File file;
        private RandomAccessFile raf;
        private int cap;
        private int head;
        private int tail;
        private int count;
        private byte[] scratchBuf; // 复用缓冲：活跃窗口一次性读入（消除逐槽 seek）
        private final byte[] hdrBuf = new byte[16]; // 复用缓冲：cap/head/tail/count 一次写出（消除逐字段 seek）
        private HashMap<Long, Integer> mirror; // 惰性内存镜像：value→计数，使 contains 从 O(count) 降为 O(1)；仅被 contains/offerUnique 触发

        public DsFreeRing(File file,  int initialCap) throws IOException {
            if (initialCap <= 0) {
                throw new IllegalArgumentException("initialCap must be > 0");
            }
            this.file = file;
            openOrInit(initialCap);
        }

        public int count() throws IOException {
            lock.lock();
            try {
                reloadHeaderIfNeeded();
                return count;
            } finally {
                lock.unlock();
            }
        }

        public int size() throws IOException {
            return count();
        }

        public void clear() throws IOException {
            lock.lock();
            try {
                head = 0;
                tail = 0;
                count = 0;
                writeHeader();
                if (mirror != null) {
                    mirror.clear();
                }
            } finally {
                lock.unlock();
            }
        }

        public boolean offer(long value) throws IOException {
            lock.lock();
            try {
                if (count >= cap) {
                    expand(cap * 2);
                }
                writeAt(tail, value);
                tail = (tail + 1) % cap;
                count++;
                writeHeader();
                mirrorAdd(value);
                return true;
            } finally {
                lock.unlock();
            }
        }

        public boolean offerUnique(long value) throws IOException {
            lock.lock();
            try {
                if (containsLocked(value)) {
                    return false;
                }
                if (count >= cap) {
                    expand(cap * 2);
                }
                writeAt(tail, value);
                tail = (tail + 1) % cap;
                count++;
                writeHeader();
                mirrorAdd(value);
                return true;
            } finally {
                lock.unlock();
            }
        }

        public long poll() throws IOException {
            lock.lock();
            try {
                if (count <= 0L) {
                    return -1L;
                }
                long value = readAt(head);
                head = (head + 1) % cap;
                count--;
                writeHeader();
                mirrorDel(value);
                return value;
            } finally {
                lock.unlock();
            }
        }

        public boolean contains(long value) throws IOException {
            lock.lock();
            try {
                return containsLocked(value);
            } finally {
                lock.unlock();
            }
        }

        public boolean remove(long value) throws IOException {
            lock.lock();
            try {
                if (count <= 0) {
                    return false;
                }
                if (mirror != null && !mirror.containsKey(value)) {
                    return false;
                }
                int idx = findSlotLocked(value);
                if (idx < 0) {
                    return false;
                }
                removeSlotLocked(idx);
                writeHeader();
                mirrorDel(value);
                return true;
            } finally {
                lock.unlock();
            }
        }

        public long[] snapshot() throws IOException {
            lock.lock();
            try {
                long[] out = new long[count];
                final int[] w = {0};
                forEachSlotLocked(v -> out[w[0]++] = v);
                return out;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() throws IOException {
            lock.lock();
            try {
                if (raf != null) {
                    raf.close();
                    raf = null;
                }
            } finally {
                lock.unlock();
            }
        }

        private boolean containsLocked(long value) throws IOException {
            mirrorEnsure();
            return mirror.containsKey(value);
        }

        /** 惰性建立内存镜像：一次性扫描活跃窗口，构建 value→计数 映射，此后 contains 为 O(1)。 */
        private void mirrorEnsure() throws IOException {
            if (mirror != null) {
                return;
            }
            HashMap<Long, Integer> m = new HashMap<>();
            int n = count;
            if (n > 0) {
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(loadWindowLocked(), 0, n * 8);
                for (int k = 0; k < n; k++) {
                    m.merge(bb.getLong(), 1, Integer::sum);
                }
            }
            mirror = m;
        }

        private void mirrorAdd(long value) {
            if (mirror != null) {
                mirror.merge(value, 1, Integer::sum);
            }
        }

        private void mirrorDel(long value) {
            if (mirror == null) {
                return;
            }
            Integer c = mirror.get(value);
            if (c == null) {
                return;
            }
            if (c == 1) {
                mirror.remove(value);
            } else {
                mirror.put(value, c - 1);
            }
        }

        private int findSlotLocked(long value) throws IOException {
            int n = count;
            if (n <= 0) {
                return -1;
            }
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(loadWindowLocked(), 0, n * 8);
            for (int k = 0; k < n; k++) {
                if (bb.getLong() == value) {
                    int slot = head + k;
                    return (slot >= cap) ? slot - cap : slot;
                }
            }
            return -1;
        }

        private void forEachSlotLocked(LongConsumer consumer) throws IOException {
            int n = count;
            if (n <= 0) {
                return;
            }
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(loadWindowLocked(), 0, n * 8);
            for (int k = 0; k < n; k++) {
                consumer.accept(bb.getLong());
            }
        }

        /** 一次性读入活跃窗口 [head, head+count) 的原始字节（最多 2 次连续读），避免逐槽 seek。 */
        private byte[] loadWindowLocked() throws IOException {
            int n = count;
            byte[] b = scratch(n * 8);
            int first = Math.min(n, cap - head);
            raf.seek(OFF_DATA + (long) head * 8L);
            raf.readFully(b, 0, first * 8);
            if (n > first) {
                raf.seek(OFF_DATA);
                raf.readFully(b, first * 8, (n - first) * 8);
            }
            return b;
        }

        private byte[] scratch(int need) {
            if (scratchBuf == null || scratchBuf.length < need) {
                scratchBuf = new byte[Math.max(need, 256)];
            }
            return scratchBuf;
        }

        private void removeSlotLocked(int idx) throws IOException {
            int lastIdx = (tail + cap - 1) % cap;
            if (idx != lastIdx) {
                writeAt(idx, readAt(lastIdx));
            }
            tail = lastIdx;
            count--;
        }

        private void openOrInit(int initialCap) throws IOException {
            lock.lock();
            try {
                raf = new RandomAccessFile(file, "rw");
                if (raf.length() < HEADER_BYTES) {
                    initNew(initialCap);
                    return;
                }
                if (!magicMatches()) {
                    initNew(initialCap);
                    return;
                }
                readHeader();
                if (cap <= 0) {
                    initNew(initialCap);
                }
            } finally {
                lock.unlock();
            }
        }

        private void reloadHeaderIfNeeded() throws IOException {
            if (raf == null) {
                throw new IOException("free ring is closed");
            }
        }

        private void initNew(int initialCap) throws IOException {
            raf.setLength(0L);
            raf.seek(OFF_MAGIC);
            raf.write(MAGIC);
            raf.seek(OFF_CAP);
            raf.writeInt(initialCap);
            raf.seek(OFF_HEAD);
            raf.writeInt(0);
            raf.seek(OFF_TAIL);
            raf.writeInt(0);
            raf.seek(OFF_COUNT);
            raf.writeInt(0);

            if (raf.length() < OFF_DATA + initialCap * 8L) {
                raf.setLength(OFF_DATA + initialCap * 8L);
            }
            cap = initialCap;
            head = 0;
            tail = 0;
            count = 0;
            mirror = null;
        }

        private boolean magicMatches() throws IOException {
            raf.seek(0L);
            byte[] m = new byte[4];
            raf.readFully(m);
            return m[0] == MAGIC[0] && m[1] == MAGIC[1] && m[2] == MAGIC[2] && m[3] == MAGIC[3];
        }

        private void readHeader() throws IOException {
            raf.seek(OFF_CAP);
            cap = raf.readInt();
            raf.seek(OFF_HEAD);
            head = raf.readInt();
            raf.seek(OFF_TAIL);
            tail = raf.readInt();
            raf.seek(OFF_COUNT);
            count = raf.readInt();

            if (cap < 1) {
                cap = 0;
                head = 0;
                tail = 0;
                count = 0;
                return;
            }
            if (head < 0 || head >= cap) {
                head = 0;
            }
            if (tail < 0 || tail >= cap) {
                tail = 0;
            }
            if (count < 0 || count > cap) {
                count = 0;
                head = 0;
                tail = 0;
            }
        }

        private void writeHeader() throws IOException {
            putInt(hdrBuf, 0, cap);
            putInt(hdrBuf, 4, head);
            putInt(hdrBuf, 8, tail);
            putInt(hdrBuf, 12, count);
            raf.seek(OFF_CAP);
            raf.write(hdrBuf);
        }

        private static void putInt(byte[] b, int off, int v) {
            b[off] = (byte) (v >>> 24);
            b[off + 1] = (byte) (v >>> 16);
            b[off + 2] = (byte) (v >>> 8);
            b[off + 3] = (byte) v;
        }

        private long readAt(long slot) throws IOException {
            raf.seek(OFF_DATA + slot * 8L);
            return raf.readLong();
        }

        private void writeAt(long slot, long value) throws IOException {
            raf.seek(OFF_DATA + slot * 8L);
            raf.writeLong(value);
        }

        private void expand(int newCap) throws IOException {
            final int oldCap = cap;
            if (raf.length() < OFF_DATA + newCap * 8L) {
                raf.setLength(OFF_DATA + newCap * 8L);
            }
            if (head + count > oldCap) {
                final int wrapCount = (head + count) - oldCap;
                for (int k = 0; k < wrapCount; k++) {
                    writeAt((long)oldCap + k, readAt(k));
                }
            }
            cap = newCap;
            tail = (head + count) % cap;
            writeHeader();
        }

    public int capacity() {
        return cap;
    }

    public int getCount() {
        return count;
    }

}