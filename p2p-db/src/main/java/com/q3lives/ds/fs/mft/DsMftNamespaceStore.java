package com.q3lives.ds.fs.mft;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.core.DsString;
import com.q3lives.ds.fs.Ds256DirectoryStore;
import com.q3lives.ds.index.value.DsTagsManyToManyStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

public final class DsMftNamespaceStore implements AutoCloseable {

    private final ReentrantLock lock = new ReentrantLock();

    private final Path namespaceDir;
    private final boolean atimeEnabled;

    private final Ds256DirectoryStore dirStore;
    private final DsFixedBucketStore dataStore;
    private final DsHashMap fileIdToBucketId;
    private final DsHashMap fileIdToAtimeMillis;
    private final DsHashMap fileIdToInheritedMgrId;
    private final DsString longNameStore;
    private final DsTagsManyToManyStore tagsStore;

    public static DsMftNamespaceStore openFromYaml() {
        DsMftFileSystemConfigLoader.LoadedConfig loaded = DsMftFileSystemConfigLoader.load();
        return new DsMftNamespaceStore(Path.of(loaded.config.getNamespaceDir()), loaded.config);
    }

    public DsMftNamespaceStore(Path namespaceDir, DsMftFileSystemConfig cfg) {
        if (cfg == null) {
            throw new IllegalArgumentException("cfg is null");
        }
        try {
            this.namespaceDir = namespaceDir.toAbsolutePath().normalize();
            Files.createDirectories(this.namespaceDir);
            this.atimeEnabled = cfg.isAtimeEnabled();

            Path dirBlocks = this.namespaceDir.resolve("dir_blocks");
            Files.createDirectories(dirBlocks);
            this.dirStore = new Ds256DirectoryStore(dirBlocks.toString());

            Path dataHome = this.namespaceDir.resolve("data");
            Files.createDirectories(dataHome);
            this.dataStore = new DsFixedBucketStore(dataHome.toString());

            Path maps = this.namespaceDir.resolve("maps");
            Files.createDirectories(maps);
            this.fileIdToBucketId = new DsHashMap(maps.resolve("file_data.map").toFile());
            this.fileIdToBucketId.setSyncModeStrong100ms();
            this.fileIdToInheritedMgrId = new DsHashMap(maps.resolve("inherited.map").toFile());
            this.fileIdToInheritedMgrId.setSyncModeStrong100ms();
            if (this.atimeEnabled) {
                this.fileIdToAtimeMillis = new DsHashMap(maps.resolve("atime.map").toFile());
                this.fileIdToAtimeMillis.setSyncModeStrong100ms();
            } else {
                this.fileIdToAtimeMillis = null;
            }

            Path names = this.namespaceDir.resolve("names");
            Files.createDirectories(names);
            this.longNameStore = new DsString(names.toString());

            Path tags = this.namespaceDir.resolve("tags");
            Files.createDirectories(tags);
            this.tagsStore = new DsTagsManyToManyStore(tags, cfg.getTagsInitialRingCap());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Path getNamespaceDir() {
        return namespaceDir;
    }

    public boolean isAtimeEnabled() {
        return atimeEnabled;
    }

    public Ds256DirectoryStore dirStore() {
        return dirStore;
    }

    public DsFixedBucketStore dataStore() {
        return dataStore;
    }

    public DsHashMap fileIdToBucketIdMap() {
        return fileIdToBucketId;
    }

    public DsHashMap fileIdToInheritedMgrIdMap() {
        return fileIdToInheritedMgrId;
    }

    public DsHashMap fileIdToAtimeMillisMap() {
        return fileIdToAtimeMillis;
    }

    public DsString longNameStore() {
        return longNameStore;
    }

    public DsTagsManyToManyStore tagsStore() {
        return tagsStore;
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            try {
                tagsStore.close();
            } finally {
                longNameStore.close();
                fileIdToBucketId.close();
                fileIdToInheritedMgrId.close();
                if (fileIdToAtimeMillis != null) {
                    fileIdToAtimeMillis.close();
                }
                dataStore.close();
                dirStore.close();
            }
        } finally {
            lock.unlock();
        }
    }
}

