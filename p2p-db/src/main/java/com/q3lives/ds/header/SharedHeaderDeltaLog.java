package com.q3lives.ds.header;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class SharedHeaderDeltaLog implements Closeable {

    public static final int STORE_HEADER_MAX_BYTES = 512;

    private static final long SESSION_HIGH;
    private static final long SESSION_LOW;

    static {
        java.util.UUID u = java.util.UUID.randomUUID();
        SESSION_HIGH = u.getMostSignificantBits();
        SESSION_LOW = u.getLeastSignificantBits();
    }

    private final File tierDir;
    private final String dayKey;
    private final File logFile;
    private final ReentrantLock writeLock = new ReentrantLock();
    private RandomAccessFile raf;
    private final ConcurrentHashMap<Long, StoreState> states = new ConcurrentHashMap<>();
    private final AtomicLong pageSeq = new AtomicLong(0L);
    private final byte[] zeroPage = new byte[SharedHeaderLogLayout.PAGE_SIZE];
    private final StoreIdRegistry registry;
    private final SharedHeaderDeltaLog yesterdayLog;

    public SharedHeaderDeltaLog(File tierDir, String dayKey, StoreIdRegistry registry) throws IOException {
        this(tierDir, dayKey, registry, null);
    }

    SharedHeaderDeltaLog(File tierDir, String dayKey, StoreIdRegistry registry, SharedHeaderDeltaLog yesterdayLog) throws IOException {
        if (tierDir == null) throw new NullPointerException("tierDir");
        if (registry == null) throw new NullPointerException("registry");
        this.tierDir = tierDir;
        if (!this.tierDir.exists()) this.tierDir.mkdirs();
        this.dayKey = dayKey == null || dayKey.isEmpty() ? "today" : dayKey;
        this.registry = registry;
        this.yesterdayLog = yesterdayLog;
        this.logFile = new File(tierDir, "delta_headers_" + this.dayKey + ".log");
        openOrInit();
    }

    public StoreIdRegistry getRegistry() { return registry; }

    public File getLogFile() { return logFile; }

    public SharedHeaderDeltaLog getYesterdayLog() { return yesterdayLog; }

    static String yesterdayKeyFor(String dayKey) {
        try {
            java.time.LocalDate d;
            if (dayKey == null || dayKey.isEmpty()) {
                d = java.time.LocalDate.now().minusDays(1);
            } else {
                d = java.time.LocalDate.parse(dayKey).minusDays(1);
            }
            return d.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static SharedHeaderDeltaLog createWithYesterdayRecovery(File tierDir, String dayKey, StoreIdRegistry registry) throws IOException {
        if (tierDir == null || registry == null) throw new NullPointerException();
        SharedHeaderDeltaLog yesterday = null;
        String yk = yesterdayKeyFor(dayKey);
        if (yk != null) {
            File ylog = new File(tierDir, "delta_headers_" + yk + ".log");
            if (ylog.exists()) {
                try {
                    yesterday = new SharedHeaderDeltaLog(tierDir, yk, registry);
                } catch (Throwable ignore) { yesterday = null; }
            }
        }
        return new SharedHeaderDeltaLog(tierDir, dayKey, registry, yesterday);
    }

    public long internStoreId(String relativePath) throws IOException {
        return registry.intern(relativePath);
    }

    public Long findStoreIdIfReady(String relativePath) {
        return registry.lookupIfRegistered(relativePath);
    }

    private void openOrInit() throws IOException {
        raf = new RandomAccessFile(logFile, "rw");
        boolean initFresh = raf.length() < SharedHeaderLogLayout.LOG_FILE_HEADER_SIZE;
        if (!initFresh) {
            raf.seek(0);
            int magic = raf.readInt();
            if (magic != SharedHeaderLogLayout.LOG_MAGIC) {
                initFresh = true;
            } else {
                raf.seek(SharedHeaderLogLayout.OFF_LOG_SESSION_HIGH);
                long high = raf.readLong();
                long low = raf.readLong();
                if (high != SESSION_HIGH || low != SESSION_LOW) initFresh = true;
            }
        }
        if (initFresh) {
            raf.setLength(0);
            byte[] header = new byte[SharedHeaderLogLayout.LOG_FILE_HEADER_SIZE];
            ByteBuffer bb = ByteBuffer.wrap(header);
            bb.putInt(SharedHeaderLogLayout.OFF_LOG_MAGIC, SharedHeaderLogLayout.LOG_MAGIC);
            bb.putInt(SharedHeaderLogLayout.OFF_LOG_VERSION, SharedHeaderLogLayout.LOG_VERSION);
            bb.putLong(SharedHeaderLogLayout.OFF_LOG_CREATE_EPOCH, System.currentTimeMillis());
            bb.putLong(SharedHeaderLogLayout.OFF_LOG_NEXT_STORE_ID, registry.nextAssignedStoreId());
            bb.putLong(SharedHeaderLogLayout.OFF_LOG_FLAGS, 0L);
            bb.putLong(SharedHeaderLogLayout.OFF_LOG_SESSION_HIGH, SESSION_HIGH);
            bb.putLong(SharedHeaderLogLayout.OFF_LOG_SESSION_LOW, SESSION_LOW);
            raf.write(header);
        } else {
            raf.seek(0);
            int magic = raf.readInt();
            if (magic != SharedHeaderLogLayout.LOG_MAGIC) {
                throw new IOException("Shared delta log corrupted: bad magic " + Integer.toHexString(magic));
            }
            replayAllPages();
        }
    }

    private void replayAllPages() throws IOException {
        long flen = raf.length();
        long pos = SharedHeaderLogLayout.LOG_FILE_HEADER_SIZE;
        int ps = SharedHeaderLogLayout.PAGE_SIZE;
        while (pos + ps <= flen) {
            raf.seek(pos);
            byte[] page = new byte[ps];
            raf.readFully(page);
            int mag = ByteBuffer.wrap(page, SharedHeaderLogLayout.OFF_PAGE_MAGIC, 4).getInt();
            if (mag != SharedHeaderLogLayout.SLOT_MAGIC) {
                pos += ps;
                continue;
            }
            int pageCrcStored = ByteBuffer.wrap(page, SharedHeaderLogLayout.OFF_PAGE_CRC32, 4).getInt();
            page[SharedHeaderLogLayout.OFF_PAGE_CRC32 + 0] = 0;
            page[SharedHeaderLogLayout.OFF_PAGE_CRC32 + 1] = 0;
            page[SharedHeaderLogLayout.OFF_PAGE_CRC32 + 2] = 0;
            page[SharedHeaderLogLayout.OFF_PAGE_CRC32 + 3] = 0;
            int pageCrcCalc = crc32(page, 0, ps);
            if (pageCrcCalc != pageCrcStored) {
                pos += ps;
                continue;
            }
            int slotCount = ByteBuffer.wrap(page, SharedHeaderLogLayout.OFF_PAGE_SLOT_COUNT, 4).getInt();
            if (slotCount < 0 || slotCount > 256) {
                pos += ps;
                continue;
            }
            int sOff = SharedHeaderLogLayout.PAGE_HEADER_SIZE;
            for (int s = 0; s < slotCount; s++) {
                if (sOff + SharedHeaderLogLayout.SLOT_HEADER_SIZE > ps) break;
                int smag = ByteBuffer.wrap(page, sOff, 4).getInt();
                if (smag != SharedHeaderLogLayout.SLOT_MAGIC) break;
                long sid = ByteBuffer.wrap(page, sOff + SharedHeaderLogLayout.OFF_SLOT_STORE_ID, 8).getLong();
                long slotSeq = ByteBuffer.wrap(page, sOff + SharedHeaderLogLayout.OFF_SLOT_SEQ, 8).getLong();
                int len = ByteBuffer.wrap(page, sOff + SharedHeaderLogLayout.OFF_SLOT_LEN, 2).getShort() & 0xFFFF;
                int flags = ByteBuffer.wrap(page, sOff + SharedHeaderLogLayout.OFF_SLOT_FLAGS, 2).getShort() & 0xFFFF;
                int tier = flags & SharedHeaderLogLayout.SLOT_FLAG_TIER_MASK;
                int payloadSize = SharedHeaderLogLayout.payloadSizeForTier(tier);
                if (payloadSize <= 0) payloadSize = SharedHeaderLogLayout.SLOT_SIZE_XL;
                if (len < 0 || len > payloadSize || payloadSize > STORE_HEADER_MAX_BYTES) break;
                int totalSlot = SharedHeaderLogLayout.SLOT_HEADER_SIZE + payloadSize;
                if (sOff + totalSlot > ps) break;
                StoreState st = states.computeIfAbsent(sid, k -> new StoreState());
                if (slotSeq >= st.seq) {
                    byte[] snap = new byte[STORE_HEADER_MAX_BYTES];
                    BitSet bs = new BitSet(STORE_HEADER_MAX_BYTES);
                    for (int i = 0; i < payloadSize; i++) {
                        byte v = page[sOff + SharedHeaderLogLayout.SLOT_HEADER_SIZE + i];
                        snap[i] = v;
                        bs.set(i);
                    }
                    st.snapshot = snap;
                    st.dirtyBytes = bs;
                    st.seq = slotSeq;
                }
                sOff += totalSlot;
            }
            long seq = ByteBuffer.wrap(page, SharedHeaderLogLayout.OFF_PAGE_SEQ, 8).getLong();
            if (seq > pageSeq.get()) pageSeq.set(seq);
            pos += ps;
        }
    }

    public byte[] getReadSnapshot(long storeId) {
        StoreState s = states.get(storeId);
        byte[] primary = null;
        if (s != null) {
            synchronized (s) {
                if (s.snapshot != null) {
                    primary = new byte[STORE_HEADER_MAX_BYTES];
                    System.arraycopy(s.snapshot, 0, primary, 0, STORE_HEADER_MAX_BYTES);
                }
            }
        }
        if (primary != null) return primary;
        SharedHeaderDeltaLog y = yesterdayLog;
        if (y == null) return null;
        return y.getReadSnapshot(storeId);
    }

    public BitSet getDirtyBitSet(long storeId) {
        StoreState s = states.get(storeId);
        BitSet primary = null;
        if (s != null) {
            synchronized (s) {
                primary = s.dirtyBytes == null ? null : (BitSet) s.dirtyBytes.clone();
            }
        }
        if (primary != null && !primary.isEmpty()) return primary;
        SharedHeaderDeltaLog y = yesterdayLog;
        if (y == null) return primary;
        BitSet yb = y.getDirtyBitSet(storeId);
        if (yb == null || yb.isEmpty()) return primary;
        if (primary == null || primary.isEmpty()) return yb;
        BitSet out = (BitSet) primary.clone();
        out.or(yb);
        return out;
    }

    public void markAndAppendIfNeeded(long storeId, ByteBuffer base, int fieldOffset, int fieldLen) throws IOException {
        if (base == null || fieldOffset < 0 || fieldLen <= 0) return;
        if (fieldOffset + fieldLen > STORE_HEADER_MAX_BYTES) {
            fieldLen = STORE_HEADER_MAX_BYTES - fieldOffset;
            if (fieldLen <= 0) return;
        }
        StoreState st = states.computeIfAbsent(storeId, k -> new StoreState());
        boolean needAppend;
        synchronized (st) {
            if (st.snapshot == null) {
                st.snapshot = new byte[STORE_HEADER_MAX_BYTES];
                st.dirtyBytes = new BitSet(STORE_HEADER_MAX_BYTES);
            }
            int changed = 0;
            for (int i = fieldOffset, e = fieldOffset + fieldLen; i < e; i++) {
                byte v = base.get(i);
                if (st.snapshot[i] != v) {
                    st.snapshot[i] = v;
                    changed++;
                }
                st.dirtyBytes.set(i);
            }
            st.pendingBytes += changed;
            needAppend = !st.pagePending;
            if (needAppend) st.pagePending = true;
        }
        if (needAppend) flushPendingStoreLocked(storeId);
    }

    public void markFullAndAppend(long storeId, ByteBuffer base) throws IOException {
        if (base == null) return;
        int len = Math.min(base.capacity(), STORE_HEADER_MAX_BYTES);
        StoreState st = states.computeIfAbsent(storeId, k -> new StoreState());
        synchronized (st) {
            if (st.snapshot == null) {
                st.snapshot = new byte[STORE_HEADER_MAX_BYTES];
                st.dirtyBytes = new BitSet(STORE_HEADER_MAX_BYTES);
            }
            for (int i = 0; i < len; i++) {
                byte v = base.get(i);
                st.snapshot[i] = v;
                st.dirtyBytes.set(i);
            }
            st.pagePending = true;
        }
        flushPendingStoreLocked(storeId);
    }

    public void flushAll() throws IOException {
        writeLock.lock();
        try {
            if (raf != null) raf.getChannel().force(true);
        } finally {
            writeLock.unlock();
        }
    }

    public int liveStoreCount() {
        return states.size();
    }

    private void flushPendingStoreLocked(long storeId) throws IOException {
        StoreState st = states.get(storeId);
        if (st == null) return;
        byte[] payload;
        int slotPayload;
        long writeSeq;
        int slotTier;
        synchronized (st) {
            if (!st.pagePending || st.snapshot == null) return;
            int dirtyEnd = st.dirtyBytes.length();
            int snapEnd = STORE_HEADER_MAX_BYTES;
            while (snapEnd > 0 && st.snapshot[snapEnd - 1] == 0 && (snapEnd - 1) >= dirtyEnd) {
                snapEnd--;
            }
            int logicEnd = Math.max(dirtyEnd, snapEnd);
            slotTier = SharedHeaderLogLayout.tierForDirtyEnd(logicEnd);
            slotPayload = SharedHeaderLogLayout.payloadSizeForTier(slotTier);
            payload = new byte[slotPayload];
            int copyLen = Math.min(STORE_HEADER_MAX_BYTES, slotPayload);
            System.arraycopy(st.snapshot, 0, payload, 0, copyLen);
            st.seq = st.seq + 1;
            writeSeq = st.seq;
        }
        writeLock.lock();
        try {
            ensureOpenLocked();
            int ps = SharedHeaderLogLayout.PAGE_SIZE;
            long flen = raf.length();
            long pageStart;
            boolean firstPage = false;
            if (flen <= SharedHeaderLogLayout.LOG_FILE_HEADER_SIZE) {
                pageStart = SharedHeaderLogLayout.LOG_FILE_HEADER_SIZE;
                firstPage = true;
            } else {
                long delta = flen - SharedHeaderLogLayout.LOG_FILE_HEADER_SIZE;
                long remainder = delta % ps;
                if (remainder == 0) {
                    pageStart = flen;
                    firstPage = true;
                } else {
                    pageStart = flen - remainder;
                }
            }
            byte[] page;
            int slotCount;
            int perSlot = SharedHeaderLogLayout.SLOT_HEADER_SIZE + slotPayload;
            int maxSlots = SharedHeaderLogLayout.maxSlotsForPage(slotPayload);
            if (firstPage) {
                page = new byte[ps];
                System.arraycopy(zeroPage, 0, page, 0, ps);
                slotCount = 0;
                ByteBuffer ph = ByteBuffer.wrap(page, 0, SharedHeaderLogLayout.PAGE_HEADER_SIZE);
                ph.putInt(SharedHeaderLogLayout.SLOT_MAGIC);
                ph.putInt(0);
                ph.putLong(pageSeq.incrementAndGet());
                ph.putInt(0);
                ph.putInt(0);
            } else {
                page = new byte[ps];
                raf.seek(pageStart);
                raf.readFully(page);
                slotCount = ByteBuffer.wrap(page, SharedHeaderLogLayout.OFF_PAGE_SLOT_COUNT, 4).getInt();
                if (slotCount < 0) slotCount = 0;
                boolean needNewPage = slotCount >= maxSlots;
                int sOff = SharedHeaderLogLayout.PAGE_HEADER_SIZE + slotCount * perSlot;
                if (sOff + perSlot > ps) needNewPage = true;
                if (needNewPage) {
                    long p1 = raf.length();
                    long remainderAfter = (p1 - SharedHeaderLogLayout.LOG_FILE_HEADER_SIZE) % ps;
                    if (remainderAfter != 0) {
                        raf.setLength(p1 + (ps - remainderAfter));
                    }
                    pageStart = raf.length();
                    page = new byte[ps];
                    System.arraycopy(zeroPage, 0, page, 0, ps);
                    slotCount = 0;
                    ByteBuffer ph = ByteBuffer.wrap(page, 0, SharedHeaderLogLayout.PAGE_HEADER_SIZE);
                    ph.putInt(SharedHeaderLogLayout.SLOT_MAGIC);
                    ph.putInt(0);
                    ph.putLong(pageSeq.incrementAndGet());
                    ph.putInt(0);
                    ph.putInt(0);
                    firstPage = true;
                }
            }
            int sOff = SharedHeaderLogLayout.PAGE_HEADER_SIZE + slotCount * perSlot;
            if (sOff + perSlot > ps) {
                throw new IOException("page overflow: perSlot=" + perSlot + " sOff=" + sOff + " ps=" + ps);
            }
            ByteBuffer sh = ByteBuffer.wrap(page, sOff, SharedHeaderLogLayout.SLOT_HEADER_SIZE + slotPayload);
            sh.putInt(SharedHeaderLogLayout.SLOT_MAGIC);
            sh.putLong(storeId);
            sh.putLong(writeSeq);
            sh.putShort((short) (slotPayload & 0xFFFF));
            short flags = (short) (slotTier & SharedHeaderLogLayout.SLOT_FLAG_TIER_MASK);
            sh.putShort(flags);
            sh.put(payload, 0, slotPayload);
            slotCount++;
            ByteBuffer.wrap(page, SharedHeaderLogLayout.OFF_PAGE_SLOT_COUNT, 4).putInt(slotCount);
            ByteBuffer.wrap(page, SharedHeaderLogLayout.OFF_PAGE_CRC32, 4).putInt(0);
            int pageCrc = crc32(page, 0, ps);
            ByteBuffer.wrap(page, SharedHeaderLogLayout.OFF_PAGE_CRC32, 4).putInt(pageCrc);
            raf.seek(pageStart);
            raf.write(page);
            raf.getChannel().force(true);
        } finally {
            writeLock.unlock();
            synchronized (st) {
                st.pagePending = false;
                st.pendingBytes = 0;
            }
        }
    }

    public void rollover(String newDayKey) throws IOException {
        writeLock.lock();
        try {
            if (raf != null) {
                try { raf.getChannel().force(true); } finally { raf.close(); }
                raf = null;
            }
            states.clear();
            if (newDayKey != null && !newDayKey.isEmpty()) {
                File newFile = new File(tierDir, "delta_headers_" + newDayKey + ".log");
                if (logFile.exists()) {
                    if (!logFile.renameTo(newFile)) {
                        java.io.FileInputStream in = new java.io.FileInputStream(logFile);
                        try {
                            java.io.FileOutputStream out = new java.io.FileOutputStream(newFile);
                            try {
                                byte[] buf = new byte[8192];
                                int c;
                                while ((c = in.read(buf)) > 0) out.write(buf, 0, c);
                            } finally { out.close(); }
                        } finally { in.close(); }
                    }
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void ensureOpenLocked() throws IOException {
        if (raf == null) {
            if (!tierDir.exists()) tierDir.mkdirs();
            raf = new RandomAccessFile(logFile, "rw");
            if (raf.length() < SharedHeaderLogLayout.LOG_FILE_HEADER_SIZE) {
                byte[] header = new byte[SharedHeaderLogLayout.LOG_FILE_HEADER_SIZE];
                ByteBuffer bb = ByteBuffer.wrap(header);
                bb.putInt(SharedHeaderLogLayout.LOG_MAGIC);
                bb.putInt(SharedHeaderLogLayout.LOG_VERSION);
                bb.putLong(System.currentTimeMillis());
                bb.putLong(registry.nextAssignedStoreId());
                bb.putLong(0L);
                bb.putLong(0L);
                raf.write(header);
            }
        }
    }

    @Override
    public void close() throws IOException {
        writeLock.lock();
        try {
            if (raf != null) {
                try { raf.getChannel().force(true); } finally { raf.close(); }
                raf = null;
            }
        } finally {
            writeLock.unlock();
        }
    }

    static int crc16(byte[] arr, int off, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (arr[off + i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) crc = (crc << 1) ^ 0x1021;
                else crc <<= 1;
                crc &= 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }

    static int crc32(byte[] arr, int off, int len) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(arr, off, len);
        return (int) (c.getValue() & 0xFFFFFFFFL);
    }

    private static final class StoreState {
        volatile long seq;
        byte[] snapshot;
        BitSet dirtyBytes;
        volatile boolean pagePending;
        volatile int pendingBytes;
    }
}
