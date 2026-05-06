package com.q3lives.ds.collections;

import com.q3lives.ds.util.DsDataUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;

public class DsMemoryRing implements AutoCloseable {

    private static final byte[] MAGIC = new byte[]{'.', 'M', '-', 'R'};
    private static final int OFF_MAGIC = 0;
    private static final int OFF_CAP = 4;
    private static final int OFF_HEAD = 8;
    private static final int OFF_TAIL = 12;
    private static final int OFF_COUNT = 16;
    private static final int HEADER_BYTES = 20;
    private static final int OFF_DATA = HEADER_BYTES;

    private final ReentrantLock lock = new ReentrantLock();
    private ByteBuffer buffer;
    private int cap;
    private int head;
    private int tail;
    private int count;

    public DsMemoryRing(int initialCap) {
        if (initialCap <= 0) {
            throw new IllegalArgumentException("initialCap must be > 0");
        }
        openOrInit(initialCap);
       
    }
    
    public DsMemoryRing(byte[] data) {
        if(magicMatches(data)){
            cap = DsDataUtil.loadInt(data, OFF_CAP);
            buffer = ByteBuffer.allocate(OFF_DATA + cap * 8);
            openOrInit(64);
        }else{
            throw new RuntimeException("invalid magic!");
        }
    }

    public DsMemoryRing(ByteBuffer buffer) {
        this.buffer = buffer;
        openOrInit(64);
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
            for (int i = head; i < tail; i++) {//如果存在，直接返回false。
                if (value == readAt(i)) {
                    return false;
                }
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
            if (buffer != null) {
                buffer.clear();
                buffer = null;
            }
        } finally {
            lock.unlock();
        }
    }

    private void openOrInit(int initialCap)  {
        lock.lock();
        try {
            if (buffer == null) {
                buffer = ByteBuffer.allocate(OFF_DATA + initialCap * 8);
            }
            if (buffer.capacity() < HEADER_BYTES) {
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
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        } finally {
            lock.unlock();
        }
    }

    private void reloadHeaderIfNeeded() throws IOException {
        if (buffer == null) {
            throw new IOException("free ring is closed");
        }
    }

    private void initNew(int initialCap) throws IOException {
        buffer.position(0);
        buffer.put(MAGIC);
        buffer.putInt(initialCap);//cap
        buffer.putInt(0); // head
        buffer.putInt(0); // tail
        buffer.putInt(0); // count

        cap = initialCap;
        head = 0;
        tail = 0;
        count = 0;
    }

    private boolean magicMatches() {
        buffer.position(0);
        byte[] m = new byte[4];
        buffer.get(m);
        return m[0] == MAGIC[0] && m[1] == MAGIC[1] && m[2] == MAGIC[2] && m[3] == MAGIC[3];
    }
    
     private boolean magicMatches(byte[] m){
        return m[0] == MAGIC[0] && m[1] == MAGIC[1] && m[2] == MAGIC[2] && m[3] == MAGIC[3];
    }

    private void readHeader() throws IOException {
        buffer.position(OFF_CAP);
        cap = buffer.getInt();
        head = buffer.getInt();
        tail = buffer.getInt();
        count = buffer.getInt();

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
        buffer.position(OFF_CAP);
        buffer.putInt(cap);//cap
        buffer.putInt(0); // head
        buffer.putInt(0); // tail
        buffer.putInt(0); // count
    }

    private long readAt(int slot) throws IOException {
        buffer.position(OFF_DATA + slot * 8);
        return buffer.getLong();
    }

    private void writeAt(int slot, long value) throws IOException {
        buffer.position(OFF_DATA + slot * 8);
        buffer.putLong(value);
    }

    private void expand(int newCap) throws IOException {
        ByteBuffer bufferTmp = ByteBuffer.allocate(OFF_DATA + newCap * 8);
        bufferTmp.put(0, buffer, 0, OFF_DATA + cap * 8);
        cap = newCap;
        buffer = bufferTmp;
    }

    public int capacity() {
        return cap;
    }

    public int getCount() {
        return count;
    }
    
    public byte[] toBytes(){
        if(buffer.hasArray()){
            return buffer.array();
        }else{
            //byte[] out = new byte[OFF_DATA + cap * 8];
            byte[] out = new byte[OFF_DATA + count * 8];
            buffer.get(0, out);
            return out;
        }
    }

}
