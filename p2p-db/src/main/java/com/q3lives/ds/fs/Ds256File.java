package com.q3lives.ds.fs;

import com.q3lives.ds.util.DsPathUtil;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;

public final class Ds256File implements Serializable, Comparable<Ds256File> {

    private static final long serialVersionUID = 1L;

    private static volatile Ds256FileSystem defaultFileSystem;

    public static void setDefaultFileSystem(Ds256FileSystem fileSystem) {
        defaultFileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
    }

    public static Ds256FileSystem getDefaultFileSystem() {
        Ds256FileSystem fs = defaultFileSystem;
        if (fs == null) {
            throw new IllegalStateException("Ds256FileSystem 未初始化，请先调用 Ds256File.setDefaultFileSystem(...)");
        }
        return fs;
    }

    private final String path;

    public Ds256File(String pathname) {
        this.path = normalizeDsPath(Objects.requireNonNull(pathname, "pathname"));
    }

    public Ds256File(String parent, String child) {
        Objects.requireNonNull(child, "child");
        if (parent == null || parent.isEmpty()) {
            this.path = normalizeDsPath(child);
            return;
        }
        this.path = normalizeDsPath(resolve(parent, child));
    }

    public Ds256File(Ds256File parent, String child) {
        Objects.requireNonNull(child, "child");
        if (parent == null) {
            this.path = normalizeDsPath(child);
            return;
        }
        this.path = normalizeDsPath(resolve(parent.path, child));
    }

    public Ds256File(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("URI must be absolute");
        }
        if (uri.isOpaque()) {
            throw new IllegalArgumentException("URI must be hierarchical");
        }
        if (uri.getAuthority() != null) {
            throw new IllegalArgumentException("URI authority component must be null");
        }
        String p = uri.getPath();
        if (p == null) {
            throw new IllegalArgumentException("URI path is null");
        }
        this.path = normalizeDsPath(p);
    }

    public String getPath() {
        return path;
    }

    public String getName() {
        int idx = path.lastIndexOf('/');
        if (idx < 0) {
            if (path.equals("$")) {
                return "";
            }
            if (path.startsWith("$")) {
                return path.substring(1);
            }
            return path;
        }
        if (idx == path.length() - 1) {
            return "";
        }
        return path.substring(idx + 1);
    }

    public String getParent() {
        if (path.equals("$") || path.equals("/")) {
            return null;
        }
        if (path.startsWith("$")) {
            int idx = path.lastIndexOf('/');
            if (idx < 0) {
                return "$";
            }
            return path.substring(0, idx);
        }
        int idx = path.lastIndexOf('/');
        if (idx < 0) {
            return null;
        }
        if (idx == 0) {
            return "/";
        }
        return path.substring(0, idx);
    }

    public Ds256File getParentDsFile() {
        String parent = getParent();
        if (parent == null) {
            return null;
        }
        return new Ds256File(parent);
    }

    public boolean isAbsolute() {
        return isAbsolutePath(path);
    }

    public URI toURI() {
        try {
            return new URI("ds", null, path, null);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public FileMetadata save(byte[] content, FileMetadata metadata) throws IOException, InterruptedException {
        return getDefaultFileSystem().saveFile(path, content, metadata);
    }

    public byte[] read() throws IOException, InterruptedException, ClassNotFoundException {
        return getDefaultFileSystem().getFileContentByPath(path);
    }

    public FileMetadata getMetadata() throws IOException, InterruptedException, ClassNotFoundException {
        return getDefaultFileSystem().getMetadataByPath(path);
    }

    public long mkdirs() throws IOException {
        return getDefaultFileSystem().mkdirs(path);
    }

    public List<Long> listDir(long offset, int limit) throws IOException {
        return getDefaultFileSystem().listDir(path, offset, limit);
    }

    @Override
    public int compareTo(Ds256File other) {
        if (other == null) {
            return 1;
        }
        return this.path.compareTo(other.path);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Ds256File)) {
            return false;
        }
        Ds256File other = (Ds256File) obj;
        return this.path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public String toString() {
        return path;
    }

    private static String resolve(String parent, String child) {
        if (isAbsolutePath(child)) {
            return child;
        }
        if (parent.endsWith("/")) {
            return parent + child;
        }
        return parent + "/" + child;
    }

    private static boolean isAbsolutePath(String p) {
        return p.startsWith("/")
                || p.startsWith("$")
                || p.startsWith("group://")
                || p.startsWith("private://");
    }

    private static String normalizeDsPath(String raw) {
        if (raw.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("path must use '/'");
        }
        if (raw.startsWith("group://")) {
            return "group://" + normalizeLinuxAbsoluteOrRoot(raw.substring("group://".length()));
        }
        if (raw.startsWith("private://")) {
            return "private://" + normalizeLinuxAbsoluteOrRoot(raw.substring("private://".length()));
        }
        if (raw.startsWith("$")) {
            if (raw.equals("$")) {
                return "$";
            }
            String rest = raw.substring(1);
            if (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            if (rest.isEmpty()) {
                return "$";
            }
            String normalized = DsPathUtil.normalizeLinuxPath("/" + rest, true);
            if (normalized.equals("/")) {
                return "$";
            }
            return "$" + normalized.substring(1);
        }
        if (raw.startsWith("/")) {
            return DsPathUtil.normalizeLinuxPath(raw, true);
        }
        return DsPathUtil.normalizeLinuxPath(raw, false);
    }

    private static String normalizeLinuxAbsoluteOrRoot(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "/";
        }
        String p = raw.startsWith("/") ? raw : ("/" + raw);
        return DsPathUtil.normalizeLinuxPath(p, true);
    }
}
