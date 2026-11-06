package javax.net.p2p.server.handler;

import com.q3lives.ds.binlog.DsBinlogContext;
import com.q3lives.ds.binlog.DsBinlogLayout;
import com.q3lives.ds.binlog.DsBinlogPosition;
import com.q3lives.ds.binlog.DsBinlogStore;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.ColumnCompression;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbEntityBinlogFrameAck;
import javax.net.p2p.model.DbEntityBinlogFrameRequest;
import javax.net.p2p.model.P2PWrapper;

public class DbEntityBinlogFrameServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_ENTITY_BINLOG_FRAME;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        File tmpRoot = null;
        try {
            if (request.getCommand() != P2PCommand.DB_ENTITY_BINLOG_FRAME) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbEntityBinlogFrameRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            DsDatabaseLocal local = DsDatabaseLocal.currentOrNull();
            if (local == null) local = DsDatabaseLocal.loadOrNull();
            long applied = 0L;
            String ackDayKey = payload.endDayKey;
            long ackOffset = payload.endOffset;
            long ackSeq = payload.endSequence;
            String err = null;
            if (local != null && payload.fixedFrames != null && payload.fixedFrames.length > 0) {
                String startDayKey = payload.startDayKey != null ? payload.startDayKey : payload.endDayKey;
                String endDayKey = payload.endDayKey != null ? payload.endDayKey : startDayKey;
                tmpRoot = Files.createTempDirectory("dsbinlog_frame_").toFile();
                File storeDir = new File(tmpRoot, "_binlog");
                if (!storeDir.mkdirs()) {
                    throw new IOException("cannot create tmp storeDir " + storeDir);
                }
                writeWhole(storeDir, DsBinlogLayout.FILE_PREFIX + startDayKey + DsBinlogLayout.FIXED_SUFFIX, maybeDecompress(payload.fixedFrames));
                if (payload.dynFrames != null && payload.dynFrames.length > 0) {
                    writeWhole(storeDir, DsBinlogLayout.FILE_PREFIX + startDayKey + DsBinlogLayout.DYN_SUFFIX, maybeDecompress(payload.dynFrames));
                }
                if (payload.bytesFrames != null && payload.bytesFrames.length > 0) {
                    byte[] effBytes = maybeDecompress(payload.bytesFrames);
                    File bytesDir = new File(storeDir, DsBinlogLayout.DSBYTES_STORE_NAME);
                    if (!bytesDir.mkdirs()) throw new IOException("cannot create bytes store");
                }
                DsBinlogPosition start = new DsBinlogPosition(startDayKey, payload.startOffset, payload.startSequence);
                DsBinlogPosition end = new DsBinlogPosition(endDayKey, payload.endOffset, payload.endSequence);
                try (DsBinlogStore tmpStore = new DsBinlogStore(tmpRoot, payload.bytesFrames != null && payload.bytesFrames.length > 0)) {
                    DsBinlogContext.setInReplay(true);
                    try {
                        applied = tmpStore.replayBinlog(start, end, local::applyBinlogEntry);
                    } finally {
                        DsBinlogContext.clear();
                    }
                }
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_ENTITY_BINLOG_FRAME_ACK,
                    new DbEntityBinlogFrameAck(
                            payload.sourceServerId, payload.sourcePartitionId,
                            payload.targetServerId, payload.targetPartitionId,
                            applied, ackDayKey, ackOffset, ackSeq, err));
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        } finally {
            deleteTree(tmpRoot);
        }
    }

    private static void writeWhole(File dir, String name, byte[] bytes) throws IOException {
        File f = new File(dir, name);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(0L);
            raf.write(bytes);
        }
    }

    private static byte[] maybeDecompress(byte[] b) {
        if (b == null || b.length == 0) return b;
        if (!ColumnCompression.isCompressed(b)) return b;
        byte[] r = ColumnCompression.decompress(b);
        if (r == null) {
            throw new IllegalStateException("corrupt compressed binlog frame: header invalid or length mismatch");
        }
        return r;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteTree(c);
        }
        try { Files.deleteIfExists(f.toPath()); } catch (Throwable ignore) {}
    }
}
