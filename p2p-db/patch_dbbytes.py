import re

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DbBytes.java', 'r', encoding='utf-8') as f:
    code = f.read()

# Add imports if necessary
if 'java.io.RandomAccessFile' not in code:
    code = code.replace('import java.nio.MappedByteBuffer;', 'import java.nio.MappedByteBuffer;\nimport java.io.RandomAccessFile;\nimport java.util.concurrent.locks.ReentrantLock;\nimport java.util.concurrent.ScheduledExecutorService;\nimport java.util.concurrent.Executors;\nimport java.util.concurrent.TimeUnit;\nimport java.util.Calendar;\nimport java.util.Timer;\nimport java.util.TimerTask;')

# Add fields to DbBucket
bucket_fields = """
        private final int unitSize;
        private long nextOffset = 0;
        
        private RandomAccessFile freeRaf;
        private RandomAccessFile badRaf;
        private long freeHeadPointer = 8;
        private final ReentrantLock freeLock = new ReentrantLock();
        private final ReentrantLock badLock = new ReentrantLock();
"""

code = re.sub(r'private final int unitSize;\s*private long nextOffset = 0;', bucket_fields, code)

# Add initFreeAndBadFiles in DbBucket constructor
init_files = """
            super(file, unitSize); // DsObject 构造函数
            this.unitSize = unitSize;
            checkHeader();
            initFreeAndBadFiles(file);
"""

code = code.replace('super(file, unitSize); // DsObject 构造函数\n            this.unitSize = unitSize;\n            checkHeader();', init_files)

# Implement initFreeAndBadFiles and markFree/markBad, allocateOffset
methods = """
        private void initFreeAndBadFiles(File file) {
            try {
                File freeFileObj = new File(file.getAbsolutePath() + ".free");
                File badFileObj = new File(file.getAbsolutePath() + ".bad");
                
                freeRaf = new RandomAccessFile(freeFileObj, "rw");
                if (freeRaf.length() >= 8) {
                    freeRaf.seek(0);
                    freeHeadPointer = freeRaf.readLong();
                } else {
                    freeRaf.seek(0);
                    freeRaf.writeLong(8);
                    freeHeadPointer = 8;
                }
                
                badRaf = new RandomAccessFile(badFileObj, "rw");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private long allocateOffset() throws IOException {
            freeLock.lock();
            try {
                if (freeHeadPointer < freeRaf.length()) {
                    freeRaf.seek(freeHeadPointer);
                    long offset = freeRaf.readLong();
                    freeHeadPointer += 8;
                    freeRaf.seek(0);
                    freeRaf.writeLong(freeHeadPointer);
                    return offset;
                }
            } finally {
                freeLock.unlock();
            }
            
            // fallback to nextOffset
            headerOpLock.lock();
            try {
                long offset = nextOffset;
                nextOffset += unitSize;
                headerBuffer.putLong(8, nextOffset);
                return offset;
            } finally {
                headerOpLock.unlock();
            }
        }

        public void markFree(long offset) {
            freeLock.lock();
            try {
                freeRaf.seek(freeRaf.length());
                freeRaf.writeLong(offset);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                freeLock.unlock();
            }
        }

        public void markBadBlock(long offset) {
            badLock.lock();
            try {
                badRaf.seek(badRaf.length());
                badRaf.writeLong(offset);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                badLock.unlock();
            }
        }

        public void compactFreeFile() {
            freeLock.lock();
            try {
                long len = freeRaf.length();
                if (freeHeadPointer > 8 && len > freeHeadPointer) {
                    long remaining = len - freeHeadPointer;
                    byte[] buf = new byte[(int)remaining];
                    freeRaf.seek(freeHeadPointer);
                    freeRaf.readFully(buf);
                    
                    freeRaf.seek(8);
                    freeRaf.write(buf);
                    freeRaf.setLength(8 + remaining);
                    
                    freeHeadPointer = 8;
                    freeRaf.seek(0);
                    freeRaf.writeLong(freeHeadPointer);
                } else if (freeHeadPointer > 8 && len == freeHeadPointer) {
                    freeRaf.setLength(8);
                    freeHeadPointer = 8;
                    freeRaf.seek(0);
                    freeRaf.writeLong(freeHeadPointer);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                freeLock.unlock();
            }
        }
"""

code = code.replace('private void checkHeader() {', methods + '\n        private void checkHeader() {')

# Modify add() to use allocateOffset
code = code.replace(
"""        public long add(byte[] data) throws IOException {
            headerOpLock.lock();
            try {
                long offset = nextOffset;
                // 写入数据
                writeBytes(offset, data);

                // 更新 Header
                nextOffset += unitSize;
                headerBuffer.putLong(8, nextOffset);

                return offset;
            } finally {
                headerOpLock.unlock();
            }
        }""",
"""        public long add(byte[] data) throws IOException {
            long offset = allocateOffset();
            writeBytes(offset, data);
            return offset;
        }""")

# Modify read() to catch and throw UnreadableBlockException
code = code.replace(
"""        public byte[] read(long offset) throws IOException {
            byte[] data = new byte[unitSize];
            readBytes(offset, data);
            return data;
        }""",
"""        public byte[] read(long offset) throws IOException {
            byte[] data = new byte[unitSize];
            try {
                readBytes(offset, data);
            } catch (Exception e) {
                markBadBlock(offset);
                throw new UnreadableBlockException("Block unreadable at " + offset, offset);
            }
            return data;
        }""")


# Add compaction task to DbBytes
scheduler = """
    private static ScheduledExecutorService scheduler;

    static {
        scheduler = Executors.newSingleThreadPool(r -> {
            Thread t = new Thread(r, "DbBytes-Compaction-Thread");
            t.setDaemon(true);
            return t;
        });
        
        long now = System.currentTimeMillis();
        Calendar midnight = Calendar.getInstance();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        midnight.add(Calendar.DAY_OF_MONTH, 1);
        long initialDelay = midnight.getTimeInMillis() - now;
        
        scheduler.scheduleAtFixedRate(() -> {
            compactAll();
        }, initialDelay, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
    }

    private static final java.util.List<DbBytes> instances = new java.util.ArrayList<>();

    public static void compactAll() {
        synchronized(instances) {
            for (DbBytes db : instances) {
                db.compactBuckets();
            }
        }
    }
    
    private void compactBuckets() {
        bucketLock.lock();
        try {
            for (DbBucket bucket : buckets.values()) {
                bucket.compactFreeFile();
            }
        } finally {
            bucketLock.unlock();
        }
    }
"""

code = code.replace('public class DbBytes {', 'public class DbBytes {\n' + scheduler)

# Register DbBytes instance
code = code.replace('this.bucketLock = new ReentrantLock();', 'this.bucketLock = new ReentrantLock();\n        synchronized(instances) {\n            instances.add(this);\n        }')

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DbBytes.java', 'w', encoding='utf-8') as f:
    f.write(code)

