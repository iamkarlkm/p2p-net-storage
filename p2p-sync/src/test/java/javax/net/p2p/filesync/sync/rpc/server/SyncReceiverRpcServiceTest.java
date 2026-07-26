package javax.net.p2p.filesync.sync.rpc.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import javax.net.p2p.rpc.sync.proto.SyncEventType;
import javax.net.p2p.rpc.sync.proto.SyncFinalizeRequest;
import javax.net.p2p.rpc.sync.proto.SyncEventRequest;
import org.junit.Assert;
import org.junit.Test;

public class SyncReceiverRpcServiceTest {

    @Test
    public void shouldRequireUploadThenFinalize() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_receiver_root3_");
        Path state = Files.createTempDirectory("p2p_sync_receiver_state3_");

        try (SyncReceiverStateStore store = new SyncReceiverStateStore(state)) {
            SyncEventApplier applier = new SyncEventApplier(root);
            SyncReceiverRpcService svc = new SyncReceiverRpcService(123, root, store, applier);

            long eventUid = 1000L;
            long ts = System.currentTimeMillis() - 3_000;
            javax.net.p2p.rpc.sync.proto.SyncEventAck applyAck = svc.applyEvent(SyncEventRequest.newBuilder()
                .setTaskId(1L)
                .setEventUid(eventUid)
                .setFileId(1L)
                .setPath("a/b.txt")
                .setDirectory(false)
                .setType(SyncEventType.CREATE)
                .setLastModifiedMillis(ts)
                .build());
            Assert.assertTrue(applyAck.getOk());
            Assert.assertTrue(applyAck.getNeedsUpload());
            Assert.assertEquals(123, applyAck.getStoreId());

            Path f = root.resolve("a/b.txt");
            writeUtf8(f, "hello");
            Files.setLastModifiedTime(f, FileTime.fromMillis(ts));

            javax.net.p2p.rpc.sync.proto.SyncEventAck finAck = svc.finalizeEvent(SyncFinalizeRequest.newBuilder()
                .setTaskId(1L)
                .setEventUid(eventUid)
                .setPath("a/b.txt")
                .setDirectory(false)
                .setType(SyncEventType.CREATE)
                .setLastModifiedMillis(ts)
                .build());
            Assert.assertTrue(finAck.getOk());

            javax.net.p2p.rpc.sync.proto.SyncEventAck dupAck = svc.applyEvent(SyncEventRequest.newBuilder()
                .setTaskId(1L)
                .setEventUid(eventUid)
                .setFileId(1L)
                .setPath("a/b.txt")
                .setDirectory(false)
                .setType(SyncEventType.CREATE)
                .setLastModifiedMillis(ts)
                .build());
            Assert.assertTrue(dupAck.getOk());
            Assert.assertEquals("duplicate", dupAck.getMessage());
        }
    }

    @Test
    public void shouldFailSecondWriteOnConflict() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_receiver_root_conflict_");
        Path state = Files.createTempDirectory("p2p_sync_receiver_state_conflict_");

        try (SyncReceiverStateStore store = new SyncReceiverStateStore(state)) {
            SyncEventApplier applier = new SyncEventApplier(root);
            SyncReceiverRpcService svc = new SyncReceiverRpcService(123, root, store, applier);

            long ts = System.currentTimeMillis() - 3_000;
            javax.net.p2p.rpc.sync.proto.SyncEventAck apply1 = svc.applyEvent(SyncEventRequest.newBuilder()
                .setTaskId(1L)
                .setEventUid(1001L)
                .setFileId(1L)
                .setPath("a/b.txt")
                .setDirectory(false)
                .setType(SyncEventType.MODIFY)
                .setLastModifiedMillis(ts)
                .build());
            Assert.assertTrue(apply1.getOk());
            Assert.assertTrue(apply1.getNeedsUpload());

            javax.net.p2p.rpc.sync.proto.SyncEventAck apply2 = svc.applyEvent(SyncEventRequest.newBuilder()
                .setTaskId(1L)
                .setEventUid(1002L)
                .setFileId(2L)
                .setPath("a/b.txt")
                .setDirectory(false)
                .setType(SyncEventType.MODIFY)
                .setLastModifiedMillis(ts + 1)
                .build());
            Assert.assertFalse(apply2.getOk());
            Assert.assertEquals("write_conflict", apply2.getMessage());

            Path f = root.resolve("a/b.txt");
            writeUtf8(f, "hello");
            Files.setLastModifiedTime(f, FileTime.fromMillis(ts));

            javax.net.p2p.rpc.sync.proto.SyncEventAck fin1 = svc.finalizeEvent(SyncFinalizeRequest.newBuilder()
                .setTaskId(1L)
                .setEventUid(1001L)
                .setPath("a/b.txt")
                .setDirectory(false)
                .setType(SyncEventType.MODIFY)
                .setLastModifiedMillis(ts)
                .build());
            Assert.assertTrue(fin1.getOk());

            javax.net.p2p.rpc.sync.proto.SyncEventAck apply3 = svc.applyEvent(SyncEventRequest.newBuilder()
                .setTaskId(1L)
                .setEventUid(1003L)
                .setFileId(3L)
                .setPath("a/b.txt")
                .setDirectory(false)
                .setType(SyncEventType.MODIFY)
                .setLastModifiedMillis(ts + 2)
                .build());
            Assert.assertTrue(apply3.getOk());
            Assert.assertTrue(apply3.getNeedsUpload());
        }
    }

    private static void writeUtf8(Path path, String value) throws Exception {
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }
}
