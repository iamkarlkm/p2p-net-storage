
package com.q3lives.ds.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.locks.ReentrantLock;

public  class DsFreeRing implements AutoCloseable {
        private static final byte[] MAGIC = new byte[] {'.', 'F', '-', 'R'};
        private static final int OFF_MAGIC = 0;
        private static final int OFF_HEAD = 4;
        private static final int OFF_CAP = 8;        
        private static final int OFF_TAIL = 12;
        private static final int OFF_COUNT = 16;
        private static final int HEADER_BYTES = 20;
        private static final int OFF_DATA = HEADER_BYTES;

        private final ReentrantLock lock = new ReentrantLock();
        private final File file;
        //private final File tmpFile;
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
            //this.tmpFile = tmpFile;
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
                //reloadHeaderIfNeeded();
                if (count >= cap) {
                    tail = cap;
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
                //reloadHeaderIfNeeded();
                if (count >= cap) {
                    expand(cap * 2);
                }
                for(int i=head;i<tail;i++){//如果存在，直接返回false。
                    if(value==readAt(i)) return false;
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
                //reloadHeaderIfNeeded();
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
            raf.writeInt(0); // head
            raf.seek(OFF_TAIL);
            raf.writeInt(0); // tail
            raf.seek(OFF_COUNT);
            raf.writeInt(0); // count
            raf.writeInt(0); // reserved @40
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
            raf.writeInt(0); // reserved @40
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
            cap = newCap;
            raf.setLength(OFF_DATA + newCap * 8L);
//            try (RandomAccessFile tmp = new RandomAccessFile(tmpFile, "rw")) {
//                tmp.setLength(0L);
//                tmp.seek(0L);
//                tmp.write(MAGIC);
//                tmp.seek(OFF_CAP);
//                tmp.writeLong(newCap);
//                tmp.writeLong(0L); // head
//                tmp.writeLong(count); // tail
//                tmp.writeLong(count); // count
//                tmp.writeLong(0L); // reserved @40
//                tmp.setLength(OFF_DATA + newCap * 8L);
//
//                for (long i = 0L; i < count; i++) {
//                    long slot = (head + i) % cap;
//                    long value = readAt(slot);
//                    tmp.seek(OFF_DATA + i * 8L);
//                    tmp.writeLong(value);
//                }
//            }
//
//            raf.close();
//            Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
//            raf = new RandomAccessFile(file, "rw");
//            readHeader();
        }

    public int capacity() {
        return cap;
    }

    public int getCount() {
        return count;
    }
        
        
}