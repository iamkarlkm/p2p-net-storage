package com.q3lives.ds.index.value;

import com.q3lives.ds.collections.DsManyToManyStore;
import com.q3lives.ds.core.DsString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DsTagsManyToManyStore implements AutoCloseable {

    private final DsString tagStrings;
    private final DsManyToManyStore rel;

    public DsTagsManyToManyStore(Path rootDir) {
        this(rootDir, 64);
    }

    public DsTagsManyToManyStore(Path rootDir, int initialRingCap) {
        if (initialRingCap <= 0) {
            throw new IllegalArgumentException("initialRingCap must be > 0");
        }
        try {
            Path home = rootDir.toAbsolutePath().normalize();
            Files.createDirectories(home);
            Path stringsHome = home.resolve("strings");
            Files.createDirectories(stringsHome);
            this.tagStrings = new DsString(stringsHome.toString());
            this.rel = new DsManyToManyStore(home.resolve("rel"), "tags", initialRingCap);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public long getOrCreateTagId(String tag) throws IOException {
        // DsString 自带去重与引用计数语义：相同 tag 会复用同一 id
        return tagStrings.add(tag == null ? "" : tag);
    }

    public String getTag(long tagId) throws IOException {
        return tagStrings.get(tagId);
    }

    public boolean addTagToFile(long tagId, long fileId) throws IOException {
        return rel.link(tagId, fileId);
    }

    public boolean removeTagFromFile(long tagId, long fileId) throws IOException {
        return rel.unlink(tagId, fileId);
    }

    public long[] listFilesByTag(long tagId) throws IOException {
        return rel.listRights(tagId);
    }

    public long[] listTagsByFile(long fileId) throws IOException {
        return rel.listLefts(fileId);
    }

    @Override
    public void close() throws IOException {
        try {
            rel.close();
        } finally {
            tagStrings.close();
        }
    }
}
