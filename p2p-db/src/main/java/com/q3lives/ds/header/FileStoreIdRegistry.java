package com.q3lives.ds.header;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class FileStoreIdRegistry implements StoreIdRegistry {

    public static final int MAGIC = 0x53544F52;
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 64;
    public static final int OFF_MAGIC = 0;
    public static final int OFF_VERSION = 4;
    public static final int OFF_NEXT_ID = 8;
    public static final int OFF_ENTRY_COUNT = 16;
    public static final int OFF_FLAGS = 24;

    public static final int ENTRY_HEAD = 16;
    public static final int ENTRY_OFF_ID = 0;
    public static final int ENTRY_OFF_PATH_LEN = 8;
    public static final int ENTRY_OFF_CRC16 = 10;
    public static final int ENTRY_OFF_PATH = ENTRY_HEAD;

    private final File baseDir;
    private final File indexFile;
    private RandomAccessFile raf;
    private final ConcurrentHashMap<String, Long> pathToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> idToPath = new ConcurrentHashMap<>();
    private final AtomicLong nextStoreId = new AtomicLong(1L);
    private final AtomicLong entryCount = new AtomicLong(0L);
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean ready = false;

    public FileStoreIdRegistry(File baseDir) throws IOException {
        if (baseDir == null) throw new NullPointerException("baseDir");
        this.baseDir = baseDir;
        if (!this.baseDir.exists()) this.baseDir.mkdirs();
        this.indexFile = new File(baseDir, "store_id.idx");
        open();
    }

    private synchronized void open() throws IOException {
        if (raf != null) return;
        raf = new RandomAccessFile(indexFile, "rw");
        if (raf.length() < HEADER_SIZE) {
            raf.setLength(0);
            raf.writeInt(MAGIC);
            raf.writeInt(VERSION);
            raf.writeLong(1L);
            raf.writeLong(0L);
            raf.writeLong(0L);
            raf.writeLong(0L);
            raf.write(new byte[HEADER_SIZE - (int) raf.getFilePointer()]);
        } else {
            raf.seek(0);
            int magic = raf.readInt();
            if (magic != MAGIC) {
                throw new IOException("StoreIdRegistry index corrupted: bad magic");
            }
            @SuppressWarnings("unused")
            int ver = raf.readInt();
            raf.seek(OFF_NEXT_ID);
            long nid = raf.readLong();
            if (nid < 1L) nid = 1L;
            nextStoreId.set(nid);
            long ec = raf.readLong();
            if (ec < 0L) ec = 0L;
            entryCount.set(ec);
            replayEntries();
        }
        ready = true;
    }

    private void replayEntries() throws IOException {
        long flen = raf.length();
        long pos = HEADER_SIZE;
        while (pos + ENTRY_HEAD <= flen) {
            raf.seek(pos);
            long id = raf.readLong();
            int pathLen = raf.readInt();
            int crc16 = raf.readUnsignedShort();
            if (pathLen < 0 || pathLen > 1 << 16) {
                break;
            }
            if (pos + ENTRY_HEAD + pathLen > flen) break;
            byte[] pb = new byte[pathLen];
            raf.readFully(pb);
            String path = new String(pb, StandardCharsets.UTF_8);
            int calc = crc16(path);
            if (calc != crc16) {
                break;
            }
            pathToId.putIfAbsent(path, id);
            idToPath.putIfAbsent(id, path);
            pos += ENTRY_HEAD + pathLen;
            entryCount.incrementAndGet();
        }
        long max = 0L;
        for (Long id : idToPath.keySet()) if (id != null && id > max) max = id;
        if (max + 1L > nextStoreId.get()) nextStoreId.set(max + 1L);
    }

    @Override
    public long intern(String relativePath) throws IOException {
        if (relativePath == null) throw new NullPointerException("relativePath");
        Long existing = pathToId.get(relativePath);
        if (existing != null) return existing;
        writeLock.lock();
        try {
            existing = pathToId.get(relativePath);
            if (existing != null) return existing;
            long id = nextStoreId.getAndIncrement();
            byte[] pb = relativePath.getBytes(StandardCharsets.UTF_8);
            int crc16 = crc16(relativePath);
            raf.seek(raf.length());
            raf.writeLong(id);
            raf.writeInt(pb.length);
            raf.writeShort((short) (crc16 & 0xFFFF));
            raf.write(pb);
            raf.getChannel().force(true);
            raf.seek(OFF_NEXT_ID);
            raf.writeLong(nextStoreId.get());
            raf.writeLong(entryCount.incrementAndGet());
            raf.getChannel().force(true);
            pathToId.put(relativePath, id);
            idToPath.put(id, relativePath);
            return id;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Long lookupIfRegistered(String relativePath) {
        if (relativePath == null) return null;
        return pathToId.get(relativePath);
    }

    @Override
    public String resolvePath(long storeId) {
        return idToPath.get(storeId);
    }

    @Override
    public long nextAssignedStoreId() {
        return nextStoreId.get();
    }

    @Override
    public String registryDebugName() {
        return "FileStoreIdRegistry[" + indexFile.getAbsolutePath() + "]";
    }

    @Override
    public File getBaseDir() {
        return baseDir;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void close() throws IOException {
        try {
            if (raf != null) {
                try { raf.getChannel().force(true); } finally { raf.close(); }
                raf = null;
            }
        } finally {
            ready = false;
        }
    }

    static int crc16(String s) {
        int crc = 0xFFFF;
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < b.length; i++) {
            crc ^= (b[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) crc = (crc << 1) ^ 0x1021;
                else crc <<= 1;
                crc &= 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }
}
