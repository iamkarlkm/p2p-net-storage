
package com.q3lives.ds.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
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
                int idx = findSlotLocked(value);
                if (idx < 0) {
                    return false;
                }
                removeSlotLocked(idx);
                writeHeader();
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
            return findSlotLocked(value) >= 0;
        }

        private int findSlotLocked(long value) throws IOException {
            if (count <= 0) {
                return -1;
            }
            int remain = count;
            for (int i = head; remain > 0 && i < cap; i++, remain--) {
                if (readAt(i) == value) {
                    return i;
                }
            }
            for (int i = 0; remain > 0 && i < head; i++, remain--) {
                if (readAt(i) == value) {
                    return i;
                }
            }
            return -1;
        }

        private void forEachSlotLocked(LongConsumer consumer) throws IOException {
            if (count <= 0) {
                return;
            }
            int remain = count;
            for (int i = head; remain > 0 && i < cap; i++, remain--) {
                consumer.accept(readAt(i));
            }
            for (int i = 0; remain > 0 && i < head; i++, remain--) {
                consumer.accept(readAt(i));
            }
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
            raf.seek(OFF_CAP);
            raf.writeInt(cap);
            raf.seek(OFF_HEAD);
            raf.writeInt(head);
            raf.seek(OFF_TAIL);
            raf.writeInt(tail);
            raf.seek(OFF_COUNT);
            raf.writeInt(count);
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