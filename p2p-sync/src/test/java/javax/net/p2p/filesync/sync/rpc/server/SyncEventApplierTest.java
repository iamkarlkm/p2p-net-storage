package javax.net.p2p.filesync.sync.rpc.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import javax.net.p2p.rpc.sync.proto.SyncEventRequest;
import javax.net.p2p.rpc.sync.proto.SyncEventType;
import org.junit.Assert;
import org.junit.Test;

public class SyncEventApplierTest {

    @Test
    public void shouldRejectPathTraversal() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_receiver_root_");
        SyncEventApplier applier = new SyncEventApplier(root);
        var ack = applier.apply(SyncEventRequest.newBuilder()
            .setTaskId(1L)
            .setEventUid(1L)
            .setPath("../evil")
            .setDirectory(false)
            .setType(SyncEventType.CREATE)
            .setLastModifiedMillis(System.currentTimeMillis())
            .build());
        Assert.assertFalse(ack.getOk());
    }

    @Test
    public void shouldCreateDirectoryAndFile() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_receiver_root2_");
        SyncEventApplier applier = new SyncEventApplier(root);

            var ack1 = applier.apply(SyncEventRequest.newBuilder()
                .setTaskId(1L)
                .setEventUid(100L)
                .setPath("dir1")
                .setDirectory(true)
                .setType(SyncEventType.CREATE)
                .build());
            Assert.assertTrue(ack1.getOk());
            Assert.assertTrue(Files.isDirectory(root.resolve("dir1")));

            long ts = System.currentTimeMillis() - 5_000;
            var ack2 = applier.apply(SyncEventRequest.newBuilder()
                .setTaskId(1L)
                .setEventUid(101L)
                .setPath("dir1/a.txt")
                .setDirectory(false)
                .setType(SyncEventType.CREATE)
                .setLastModifiedMillis(ts)
                .build());
            Assert.assertTrue(ack2.getOk());
            Assert.assertEquals("ok", ack2.getMessage());
            Path f1 = root.resolve("dir1/a.txt");
            Assert.assertTrue("exists=" + Files.exists(f1) + ", isDir=" + Files.isDirectory(f1), Files.isRegularFile(f1));
            long actual1 = Files.getLastModifiedTime(root.resolve("dir1/a.txt")).toMillis();
            Assert.assertTrue(Math.abs(actual1 - ts) <= 2_000);

            Files.setLastModifiedTime(root.resolve("dir1/a.txt"), FileTime.fromMillis(ts - 1_000));
            var ack4 = applier.apply(SyncEventRequest.newBuilder()
                .setTaskId(1L)
                .setEventUid(102L)
                .setPath("dir1/a.txt")
                .setDirectory(false)
                .setType(SyncEventType.MODIFY)
                .setLastModifiedMillis(ts)
                .build());
            Assert.assertTrue(ack4.getOk());
            long actual2 = Files.getLastModifiedTime(root.resolve("dir1/a.txt")).toMillis();
            Assert.assertTrue(Math.abs(actual2 - ts) <= 2_000);
    }
}
