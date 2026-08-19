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
    public static final int VERSION = 2;
    public static final int HEADER_SIZE = 64;
    public static final int OFF_MAGIC = 0;
    public static final int OFF_VERSION = 4;
    public static final int OFF_NEXT_ID = 8;
    public static final int OFF_ENTRY_COUNT = 16;
    public static final int OFF_FLAGS = 24;
    public static final int OFF_HEADER_CRC32 = 32;
    public static final int OFF_HEADER_RESERVED = 36;

    public static final int ENTRY_HEAD = 16;
    public static final int ENTRY_OFF_ID = 0;
    public static final int ENTRY_OFF_PATH_LEN = 8;
    public static final int ENTRY_OFF_CRC16 = 10;
    public static final int ENTRY_OFF_FLAGS = 12;
    public static final int ENTRY_OFF_RESERVED = 14;
    public static final int ENTRY_OFF_PATH = ENTRY_HEAD;
    public static final int ENTRY_FLAG_VALID = 0x1;
    public static final int ENTRY_OFF_TAIL_CRC32_LEN = 4;

    private static final int[] CRC32_TABLE = buildCrc32Table();

    private static int[] buildCrc32Table() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int j = 0; j < 8; j++) {
                c = (c & 1) != 0 ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            table[i] = c;
        }
        return table;
    }

    static int crc32(byte[] b, int off, int len) {
        int c = 0xFFFFFFFF;
        for (int i = off, end = off + len; i < end; i++) {
            c = CRC32_TABLE[(c ^ (b[i] & 0xFF)) & 0xFF] ^ (c >>> 8);
        }
        return c ^ 0xFFFFFFFF;
    }

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
            rewriteFreshHeaderV2();
        } else {
            raf.seek(0);
            int magic = raf.readInt();
            if (magic != MAGIC) {
                rewriteFreshHeaderV2();
            } else {
                raf.seek(OFF_VERSION);
                int ver = raf.readInt();
                if (ver < VERSION) {
                    migrateV1toV2(ver);
                }
                validateHeaderAndReplay();
            }
        }
        ready = true;
    }

    private void rewriteFreshHeaderV2() throws IOException {
        raf.setLength(0);
        byte[] head = new byte[HEADER_SIZE];
        ByteBuffer bb = ByteBuffer.wrap(head);
        bb.putInt(OFF_MAGIC, MAGIC);
        bb.putInt(OFF_VERSION, VERSION);
        bb.putLong(OFF_NEXT_ID, 1L);
        bb.putLong(OFF_ENTRY_COUNT, 0L);
        bb.putLong(OFF_FLAGS, 0L);
        int headerCrc = crc32(head, 0, OFF_HEADER_CRC32);
        bb.putInt(OFF_HEADER_CRC32, headerCrc);
        raf.write(head);
        raf.getChannel().force(true);
        nextStoreId.set(1L);
        entryCount.set(0L);
    }

    private void migrateV1toV2(int oldVer) throws IOException {
        long nextId;
        long ec;
        byte[] full;
        try {
            raf.seek(OFF_NEXT_ID);
            nextId = Math.max(1L, raf.readLong());
            ec = Math.max(0L, raf.readLong());
            long flen = raf.length();
            if (flen > Integer.MAX_VALUE) throw new IOException("registry too big: " + flen);
            full = new byte[(int) flen];
            raf.seek(0);
            raf.readFully(full);
        } catch (IOException e) {
            rewriteFreshHeaderV2();
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(full);
        bb.putInt(OFF_MAGIC, MAGIC);
        bb.putInt(OFF_VERSION, VERSION);
        bb.putLong(OFF_NEXT_ID, nextId);
        bb.putLong(OFF_ENTRY_COUNT, ec);
        bb.putLong(OFF_FLAGS, 0L);
        int headerCrc = crc32(full, 0, OFF_HEADER_CRC32);
        bb.putInt(OFF_HEADER_CRC32, headerCrc);
        raf.seek(0);
        raf.setLength(0);
        raf.write(full);
        raf.getChannel().force(true);
    }

    private void validateHeaderAndReplay() throws IOException {
        byte[] head = new byte[HEADER_SIZE];
        raf.seek(0);
        raf.readFully(head);
        ByteBuffer bb = ByteBuffer.wrap(head);
        int magic = bb.getInt(OFF_MAGIC);
        if (magic != MAGIC) {
            rewriteFreshHeaderV2();
            return;
        }
        int calcCrc = crc32(head, 0, OFF_HEADER_CRC32);
        int storedCrc = bb.getInt(OFF_HEADER_CRC32);
        long nid = bb.getLong(OFF_NEXT_ID);
        long ec = bb.getLong(OFF_ENTRY_COUNT);
        if (calcCrc != storedCrc || nid < 1L || ec < 0L) {
            rewriteFreshHeaderV2();
            return;
        }
        nextStoreId.set(nid);
        entryCount.set(0L);
        replayEntriesV2();
        long maxId = 0L;
        for (Long id : idToPath.keySet()) if (id != null && id > maxId) maxId = id;
        if (maxId + 1L > nextStoreId.get()) nextStoreId.set(maxId + 1L);
        if (entryCount.get() != ec) {
            raf.seek(OFF_NEXT_ID);
            raf.writeLong(nextStoreId.get());
            raf.writeLong(entryCount.get());
            byte[] nh = new byte[HEADER_SIZE];
            raf.seek(0);
            raf.readFully(nh);
            ByteBuffer nbb = ByteBuffer.wrap(nh);
            int nhcrc = crc32(nh, 0, OFF_HEADER_CRC32);
            nbb.putInt(OFF_HEADER_CRC32, nhcrc);
            raf.seek(0);
            raf.write(nh);
            raf.getChannel().force(true);
        }
    }

    private void replayEntriesV2() throws IOException {
        long flen = raf.length();
        long pos = HEADER_SIZE;
        byte[] scratch4 = new byte[4];
        while (pos + ENTRY_HEAD <= flen) {
            raf.seek(pos);
            long id = raf.readLong();
            int pathLen = raf.readInt();
            int crc16Path = raf.readUnsignedShort();
            int flags = raf.readUnsignedShort();
            if (pathLen < 0 || pathLen > 1 << 16) break;
            long entryEnd = pos + ENTRY_HEAD + pathLen + ENTRY_OFF_TAIL_CRC32_LEN;
            if (entryEnd > flen) break;
            byte[] pb = new byte[pathLen];
            raf.readFully(pb);
            String path;
            try {
                path = new String(pb, StandardCharsets.UTF_8);
            } catch (Throwable t) { break; }
            if ((flags & ENTRY_FLAG_VALID) == 0 || crc16(path) != crc16Path) break;
            raf.readFully(scratch4);
            int tailCrc = ByteBuffer.wrap(scratch4).getInt();
            byte[] entryFrame = new byte[ENTRY_HEAD + pathLen];
            ByteBuffer efr = ByteBuffer.wrap(entryFrame);
            efr.putLong(id);
            efr.putInt(pathLen);
            efr.putShort((short) (crc16Path & 0xFFFF));
            efr.putShort((short) (flags & 0xFFFF));
            efr.put(pb);
            int calcTail = crc32(entryFrame, 0, entryFrame.length);
            if (calcTail != tailCrc) break;
            if (id > 0L) {
                pathToId.putIfAbsent(path, id);
                idToPath.putIfAbsent(id, path);
                entryCount.incrementAndGet();
            }
            pos = entryEnd;
        }
    }

    @SuppressWarnings("unused")
    private void replayEntries() throws IOException {
        replayEntriesV2();
    }

    private void updateHeaderMeta(long nextId, long ec) throws IOException {
        byte[] nh = new byte[HEADER_SIZE];
        raf.seek(0);
        raf.readFully(nh);
        ByteBuffer bb = ByteBuffer.wrap(nh);
        bb.putLong(OFF_NEXT_ID, nextId);
        bb.putLong(OFF_ENTRY_COUNT, ec);
        int nhcrc = crc32(nh, 0, OFF_HEADER_CRC32);
        bb.putInt(OFF_HEADER_CRC32, nhcrc);
        raf.seek(0);
        raf.write(nh);
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
            int crc16Path = crc16(relativePath);
            byte[] frame = new byte[ENTRY_HEAD + pb.length + ENTRY_OFF_TAIL_CRC32_LEN];
            ByteBuffer fw = ByteBuffer.wrap(frame);
            fw.putLong(id);
            fw.putInt(pb.length);
            fw.putShort((short) (crc16Path & 0xFFFF));
            fw.putShort((short) ENTRY_FLAG_VALID);
            fw.put(pb);
            int tailCrc = crc32(frame, 0, ENTRY_HEAD + pb.length);
            fw.putInt(tailCrc);
            raf.seek(raf.length());
            raf.write(frame);
            raf.getChannel().force(true);
            long newCount = entryCount.incrementAndGet();
            updateHeaderMeta(nextStoreId.get(), newCount);
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
