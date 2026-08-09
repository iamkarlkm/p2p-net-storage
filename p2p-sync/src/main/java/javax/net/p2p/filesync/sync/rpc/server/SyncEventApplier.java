package javax.net.p2p.filesync.sync.rpc.server;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.p2p.rpc.sync.proto.SyncEventAck;
import javax.net.p2p.rpc.sync.proto.SyncEventRequest;
import javax.net.p2p.rpc.sync.proto.SyncEventType;

public final class SyncEventApplier {

    private final Path rootDir;
    private final ReentrantLock[] locks;

    public SyncEventApplier(Path rootDir) {
        this.rootDir = rootDir.toAbsolutePath().normalize();
        this.locks = new ReentrantLock[64];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public SyncEventAck apply(SyncEventRequest req) {
        if (req == null) {
            return SyncEventAck.newBuilder().setOk(false).setMessage("empty request").build();
        }
        SyncEventType t = req.getType();
        boolean renameKind = t == SyncEventType.RENAME || t == SyncEventType.MOVE;
        long targetPathHash = hashPath(req.getPath());
        long sourcePathHash = renameKind ? hashPath(req.getSourcePath()) : 0L;
        int idxT = (int) (targetPathHash ^ (targetPathHash >>> 32)) & (locks.length - 1);
        int idxS = renameKind ? (int) (sourcePathHash ^ (sourcePathHash >>> 32)) & (locks.length - 1) : idxT;
        boolean dualLock = renameKind && idxT != idxS;
        ReentrantLock first = dualLock ? (idxT < idxS ? locks[idxT] : locks[idxS]) : locks[idxT];
        ReentrantLock second = dualLock ? (idxT < idxS ? locks[idxS] : locks[idxT]) : null;
        first.lock();
        try {
            if (second != null) {
                second.lock();
            }
            try {
                Path target = resolveSafeTarget(req.getPath());
                Path source = renameKind ? resolveSafeTarget(req.getSourcePath()) : null;
                boolean ok = applyOne(req, source, target);
                if (!ok) {
                    return SyncEventAck.newBuilder().setEventUid(req.getEventUid()).setOk(false).setMessage("apply failed").build();
                }
                return SyncEventAck.newBuilder().setEventUid(req.getEventUid()).setOk(true).setMessage("ok").build();
            } finally {
                if (second != null) {
                    second.unlock();
                }
            }
        } catch (Exception e) {
            return SyncEventAck.newBuilder().setEventUid(req.getEventUid()).setOk(false).setMessage(e.getMessage() == null ? "error" : e.getMessage()).build();
        } finally {
            first.unlock();
        }
    }

    private Path resolveSafeTarget(String relativePath) {
        if (relativePath == null) {
            throw new IllegalArgumentException("path is required");
        }
        String p = relativePath.replace('\\', '/');
        if (p.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        if (p.startsWith("/") || p.startsWith("\\") || p.contains(":")) {
            throw new IllegalArgumentException("absolute path is not allowed");
        }
        for (String seg : p.split("/")) {
            if (seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException("path traversal is not allowed");
            }
        }
        Path target = rootDir.resolve(p).normalize();
        if (!target.startsWith(rootDir)) {
            throw new IllegalArgumentException("path traversal is not allowed");
        }
        return target;
    }

    private static long hashPath(String relativePath) {
        if (relativePath == null) {
            return 0L;
        }
        byte[] bytes = relativePath.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return javax.net.p2p.utils.XXHashUtil.hash64(bytes);
    }

    private boolean applyOne(SyncEventRequest req, Path source, Path target) throws IOException {
        if (req.getType() == SyncEventType.SYNC_EVENT_TYPE_UNSPECIFIED) {
            throw new IllegalArgumentException("type is required");
        }
        if (req.getDirectory()) {
            return applyDirectory(req, source, target);
        }
        return applyFile(req, source, target);
    }

    private boolean applyDirectory(SyncEventRequest req, Path source, Path target) throws IOException {
        SyncEventType t = req.getType();
        if (t == SyncEventType.CREATE) {
            Files.createDirectories(target);
            long ts = req.getLastModifiedMillis();
            if (ts > 0L) {
                Files.setLastModifiedTime(target, FileTime.fromMillis(ts));
            }
            return true;
        }
        if (t == SyncEventType.DELETE) {
            deleteRecursivelyIfExists(target);
            return true;
        }
        if (t == SyncEventType.RENAME || t == SyncEventType.MOVE) {
            if (source == null) {
                throw new IllegalArgumentException("rename/move requires source_path");
            }
            boolean sourceExists = Files.exists(source);
            boolean targetExists = Files.exists(target);
            if (!sourceExists && !targetExists) {
                throw new IllegalArgumentException("rename/move source does not exist");
            }
            if (!sourceExists && targetExists) {
                long ts = req.getLastModifiedMillis();
                if (ts > 0L) {
                    try { Files.setLastModifiedTime(target, FileTime.fromMillis(ts)); } catch (Exception ignore) {}
                }
                return true;
            }
            if (targetExists) {
                deleteRecursivelyIfExists(target);
            } else {
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            }
            try {
                Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            long ts = req.getLastModifiedMillis();
            if (ts > 0L) {
                try { Files.setLastModifiedTime(target, FileTime.fromMillis(ts)); } catch (Exception ignore) {}
            }
            return true;
        }
        throw new IllegalArgumentException("directory only supports CREATE/DELETE/RENAME/MOVE");
    }

    private boolean applyFile(SyncEventRequest req, Path source, Path target) throws IOException {
        SyncEventType t = req.getType();
        if (t == SyncEventType.DELETE) {
            Files.deleteIfExists(target);
            return true;
        }
        if (t == SyncEventType.RENAME || t == SyncEventType.MOVE) {
            if (source == null) {
                throw new IllegalArgumentException("rename/move requires source_path");
            }
            long ts = req.getLastModifiedMillis();
            if (ts == 0L) {
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try {
                    Files.createFile(target);
                } catch (FileAlreadyExistsException ignored) {
                }
                return true;
            }
            boolean sourceExists = Files.exists(source);
            boolean targetExists = Files.exists(target);
            if (!sourceExists && !targetExists) {
                throw new IllegalArgumentException("rename/move source does not exist");
            }
            if (!sourceExists && targetExists) {
                if (ts > 0L) {
                    try { Files.setLastModifiedTime(target, FileTime.fromMillis(ts)); } catch (Exception ignore) {}
                }
                return true;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (targetExists) {
                Files.deleteIfExists(target);
            }
            try {
                Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (ts > 0L) {
                try { Files.setLastModifiedTime(target, FileTime.fromMillis(ts)); } catch (Exception ignore) {}
            }
            return true;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try {
            Files.createFile(target);
        } catch (FileAlreadyExistsException ignored) {
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("target is not a regular file");
        }

        long ts = req.getLastModifiedMillis();
        if (ts > 0L) {
            Files.setLastModifiedTime(target, FileTime.fromMillis(ts));
        }
        return true;
    }

    private static void deleteRecursivelyIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
