package javax.net.p2p.filesync.sync;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.p2p.filesync.config.P2PSyncConfig;

import org.junit.Assert;
import org.junit.Test;

public class P2PDirectorySyncServiceTest {

    @Test
    public void shouldEmitCreateOnStartupScan() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_root_");
        Path state = Files.createTempDirectory("p2p_sync_state_");
        Files.createDirectories(root.resolve("sub"));
        writeUtf8(root.resolve("a.txt"), "v1");
        writeUtf8(root.resolve("sub").resolve("c.txt"), "v1");

        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(1L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());

        CountDownLatch latch = new CountDownLatch(1);
        List<FileSyncEventType> types = Collections.synchronizedList(new ArrayList<>());
        List<Boolean> dirs = Collections.synchronizedList(new ArrayList<>());

        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, (type, fileId, rel, abs, dir, acker) -> {
            types.add(type);
            dirs.add(dir);
            latch.countDown();
            acker.ack();
        })) {
            svc.start();
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }

        Assert.assertTrue(types.contains(FileSyncEventType.CREATE));
        Assert.assertTrue(dirs.contains(Boolean.TRUE));
    }

    @Test
    public void shouldEmitModifyOnRestartWhenLastModifiedChanged() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_root2_");
        Path state = Files.createTempDirectory("p2p_sync_state2_");
        Path file = root.resolve("b.txt");
        writeUtf8(file, "v1");

        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(2L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());

        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, null)) {
            svc.start();
            Thread.sleep(300);
        }

        Thread.sleep(10);
        writeUtf8(file, "v2");

        CountDownLatch latch = new CountDownLatch(1);
        List<FileSyncEventType> types = Collections.synchronizedList(new ArrayList<>());
        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, (type, fileId, rel, abs, dir, acker) -> {
            types.add(type);
            if (type == FileSyncEventType.MODIFY) {
                latch.countDown();
            }
            acker.ack();
        })) {
            svc.start();
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }

        Assert.assertTrue(types.contains(FileSyncEventType.MODIFY));
    }

    @Test
    public void shouldSkipStartupCreateWhenLastModifiedNotAfterLastRun() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_root3_");
        Path state = Files.createTempDirectory("p2p_sync_state3_");
        Path file = root.resolve("old.txt");
        writeUtf8(file, "v1");

        long lastRun = System.currentTimeMillis();
        Files.setLastModifiedTime(file, FileTime.fromMillis(lastRun - 10_000));

        try (P2PSyncStateStore store = new P2PSyncStateStore(state)) {
            store.setLastSuccessRunMillis(lastRun);
        }

        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(3L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());

        CountDownLatch latch = new CountDownLatch(1);
        List<FileSyncEventType> types = Collections.synchronizedList(new ArrayList<>());

        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, (type, fileId, rel, abs, dir, acker) -> {
            types.add(type);
            latch.countDown();
            acker.ack();
        })) {
            svc.start();
            Assert.assertFalse(latch.await(800, TimeUnit.MILLISECONDS));
        }

        Assert.assertTrue(types.isEmpty());
    }

    @Test
    public void shouldAdvanceStartupCheckpointAfterAckedFileCreate() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_root_checkpoint_ack_");
        Path state = Files.createTempDirectory("p2p_sync_state_checkpoint_ack_");
        Path file = root.resolve("acked.txt");
        writeUtf8(file, "v1");
        long lastModifiedMillis = System.currentTimeMillis() - 5000L;
        Files.setLastModifiedTime(file, FileTime.fromMillis(lastModifiedMillis));

        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(30L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());

        CountDownLatch latch = new CountDownLatch(1);
        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, (type, fileId, rel, abs, dir, acker) -> {
            if (!dir && type == FileSyncEventType.CREATE && "acked.txt".equals(rel)) {
                latch.countDown();
            }
            acker.ack();
        })) {
            svc.start();
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }

        try (P2PSyncStateStore store = new P2PSyncStateStore(state)) {
            Assert.assertEquals(lastModifiedMillis, store.getLastSuccessRunMillis());
        }
    }

    @Test
    public void shouldNotAdvanceStartupCheckpointWhileEventRemainsInflight() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_root_checkpoint_inflight_");
        Path state = Files.createTempDirectory("p2p_sync_state_checkpoint_inflight_");
        Path file = root.resolve("inflight.txt");
        writeUtf8(file, "v1");

        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(31L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());

        CountDownLatch latch = new CountDownLatch(1);
        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, (type, fileId, rel, abs, dir, acker) -> {
            if (!dir && type == FileSyncEventType.CREATE && "inflight.txt".equals(rel)) {
                latch.countDown();
            }
        })) {
            svc.start();
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
            Thread.sleep(1200L);
        }

        try (P2PSyncStateStore store = new P2PSyncStateStore(state)) {
            Assert.assertEquals(0L, store.getLastSuccessRunMillis());
        }
    }

    @Test
    public void shouldMoveToFailedQueueOnFail() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_root_failed_");
        Path state = Files.createTempDirectory("p2p_sync_state_failed_");
        writeUtf8(root.resolve("a.txt"), "v1");

        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(4L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());

        CountDownLatch latch = new CountDownLatch(1);
        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, (type, fileId, rel, abs, dir, acker) -> {
            if (type == FileSyncEventType.CREATE && !dir) {
                acker.fail("write_conflict");
                latch.countDown();
                return;
            }
            acker.ack();
        })) {
            svc.start();
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }

        try (P2PSyncStateStore store = new P2PSyncStateStore(state)) {
            long fileId = store.getOrCreateFileId("a.txt");
            Assert.assertTrue(store.fileCreatesFailed().contains(Long.valueOf(fileId)));
        }
    }

    @Test
    public void shouldTreatRenameAsDeletePlusCreate() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_root_rename_");
        Path state = Files.createTempDirectory("p2p_sync_state_rename_");
        Path oldFile = root.resolve("old.txt");
        Path newFile = root.resolve("new.txt");
        writeUtf8(oldFile, "v1");

        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(5L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());

        CountDownLatch startupLatch = new CountDownLatch(1);
        CountDownLatch renameLatch = new CountDownLatch(2);
        List<String> events = Collections.synchronizedList(new ArrayList<>());

        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, (type, fileId, rel, abs, dir, acker) -> {
            events.add(type.name() + ":" + rel);
            if ("old.txt".equals(rel) && type == FileSyncEventType.CREATE) {
                startupLatch.countDown();
            }
            if (!dir && ((type == FileSyncEventType.DELETE && "old.txt".equals(rel))
                || (type == FileSyncEventType.CREATE && "new.txt".equals(rel)))) {
                renameLatch.countDown();
            }
            acker.ack();
        })) {
            svc.start();
            Assert.assertTrue(startupLatch.await(5, TimeUnit.SECONDS));
            waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

            events.clear();
            Files.move(oldFile, newFile);

            Assert.assertTrue(events.toString(), renameLatch.await(5, TimeUnit.SECONDS));
        }

        Assert.assertTrue(events.toString(), events.contains("DELETE:old.txt"));
        Assert.assertTrue(events.toString(), events.contains("CREATE:new.txt"));
    }

    @Test
    public void shouldExcludeFilesDuringStartupScan() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_root_exclude_startup_");
        Path state = Files.createTempDirectory("p2p_sync_state_exclude_startup_");
        writeUtf8(root.resolve("keep.txt"), "v1");
        writeUtf8(root.resolve("skip.tmp"), "v1");

        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(6L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());
        cfg.setExcludeGlobs(Collections.singletonList("**/*.tmp"));

        CountDownLatch latch = new CountDownLatch(1);
        List<String> events = Collections.synchronizedList(new ArrayList<>());

        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, (type, fileId, rel, abs, dir, acker) -> {
            if (!dir) {
                events.add(type.name() + ":" + rel);
                latch.countDown();
            }
            acker.ack();
        })) {
            svc.start();
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }

        Assert.assertTrue(events.toString(), events.contains("CREATE:keep.txt"));
        Assert.assertFalse(events.toString(), events.contains("CREATE:skip.tmp"));
    }

    @Test
    public void shouldApplyIncludeGlobsToWatchEvents() throws Exception {
        Path root = Files.createTempDirectory("p2p_sync_root_include_watch_");
        Path state = Files.createTempDirectory("p2p_sync_state_include_watch_");

        P2PSyncConfig cfg = new P2PSyncConfig();
        cfg.setTaskId(7L);
        cfg.setLocalDir(root.toString());
        cfg.setDsHome(state.toString());
        cfg.setIncludeGlobs(Collections.singletonList("**/*.txt"));

        CountDownLatch latch = new CountDownLatch(1);
        List<String> events = Collections.synchronizedList(new ArrayList<>());

        try (P2PDirectorySyncService svc = new P2PDirectorySyncService(cfg, (type, fileId, rel, abs, dir, acker) -> {
            events.add(type.name() + ":" + rel);
            if ("CREATE:allowed.txt".equals(type.name() + ":" + rel)) {
                latch.countDown();
            }
            acker.ack();
        })) {
            svc.start();
            waitUntil(() -> svc.isWatchReady(), 5, TimeUnit.SECONDS);

            writeUtf8(root.resolve("allowed.txt"), "v1");
            writeUtf8(root.resolve("ignored.log"), "v1");

            Assert.assertTrue(events.toString(), latch.await(5, TimeUnit.SECONDS));
            Thread.sleep(300L);
        }

        Assert.assertTrue(events.toString(), events.contains("CREATE:allowed.txt"));
        Assert.assertFalse(events.toString(), events.contains("CREATE:ignored.log"));
    }

    private static void writeUtf8(Path path, String value) throws Exception {
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void waitUntil(CheckedBooleanSupplier condition, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50L);
        }
        Assert.fail("condition not met within timeout");
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
