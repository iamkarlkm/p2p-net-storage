package com.q3lives.ds.fs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.q3lives.ds.index.value.DsTagIndex;
import com.q3lives.ds.kv.DsSha256KV;
import com.q3lives.ds.util.DsDataUtil;
import com.q3lives.ds.util.DsPathUtil;

/**
 * 基于 bucket 层实现的“逻辑文件系统”（路径作为 Key）。
 *
 * <p>核心约定：</p>
 * <ul>
 *   <li>bucket 层不接收业务路径；路径只在本类作为 key 存在。</li>
 *   <li>路径统一 Linux 风格：绝对路径以 '/' 开头；系统空间以 '$' 为第 1 字符（例如 "$/cfg/a.txt"）。</li>
 *   <li>禁止目录穿透：任意 "." / ".." 段、非法分隔符等必须抛异常（由 {@link DsPathUtil} 校验）。</li>
 * </ul>
 *
 * <p>命名空间：</p>
 * <ul>
 *   <li>global：公共域（默认，普通绝对路径以 '/' 开头）。</li>
 *   <li>group.private：工作组/私域（按业务规则映射到此空间）。</li>
 *   <li>system：系统域（以 '$' 开头）。</li>
 * </ul>
 *
 * <p>存储组件（每个命名空间一套）：</p>
 * <ul>
 *   <li>contentStore：内容寻址（contentHash -> content bytes）。</li>
 *   <li>metaStore：文件元数据分组（fileId + groupName -> serialized meta bytes）。</li>
 *   <li>pathToFileId/pathToDirId：路径 -> fileId/dirId 映射（用 KV 存储）。</li>
 *   <li>dirStore：{@link DsDirectoryStore}，dirId -> entryId 列表（目录成员表）。</li>
 *   <li>tagIndex：{@link DsTagIndex}，tag -> fileId 列表。</li>
 * </ul>
 */
public class DsFile {

    private static final String NS_GLOBAL = "global";
    private static final String NS_GROUP_PRIVATE = "group.private";
    private static final String NS_SYSTEM = "system";

    /**
     * 创建文件系统实例。
     *
     * <p>会为 global/group.private/system 三个内部命名空间各自初始化一套 Stores（内容、元数据、目录、索引）。</p>
     *
     * @param rootDir 存储根目录
     */
    public DsFile(String rootDir) throws IOException {
        this.global = new Stores(rootDir, NS_GLOBAL);
        this.groupPrivate = new Stores(rootDir, NS_GROUP_PRIVATE);
        this.system = new Stores(rootDir, NS_SYSTEM);
    }

    private final Stores global;
    private final Stores groupPrivate;
    private final Stores system;

    /**
     * 在默认命名空间（global）写入一个文件内容并保存元数据。
     *
     * <p>该接口不指定路径：fileId 通过 globalFileId 创建/定位；内容按 sha256(content) 做内容寻址存储。</p>
     */
    public FileMetadata saveFile(byte[] content, FileMetadata metadata) throws IOException, InterruptedException {
        return saveFileInternal(global, null, content, metadata);
    }

    /**
     * 通过路径写入/更新文件内容并保存元数据。
     *
     * <p>路径解析会决定命名空间（global/system/group.private），并确保父目录存在；第一次写入时会把 fileId
     * 追加到父目录的成员表。</p>
     *
     * @param path Linux 风格路径（可为 "$/..."）
     */
    public FileMetadata saveFile(String path, byte[] content, FileMetadata metadata) throws IOException, InterruptedException {
        ParsedPath pp = parsePath(null, path);
        Stores s = stores(pp.ns);
        ensureDirPath(s, parentPath(pp.path));
        long existing = getFileIdByPath(s, pp.path);
        long fileId = existing != 0 ? existing : getOrCreateFileIdByPath(s, pp.path);
        if (existing == 0) {
            appendFileToDir(s, parentPath(pp.path), fileId);
        }
        return saveFileInternal(s, fileId, content, metadata, pp.path);
    }

    /**
     * 通过路径读取文件内容。
     *
     * <p>流程：path->fileId->BasicMeta->contentHash->content bytes。</p>
     *
     * @return 文件内容，不存在返回 null
     */
    public byte[] getFileContentByPath(String path) throws IOException, InterruptedException, ClassNotFoundException {
        ParsedPath pp = parsePath(null, path);
        Stores s = stores(pp.ns);
        long fileId = getFileIdByPath(s, pp.path);
        if (fileId == 0) {
            return null;
        }
        FileMetadata.BasicMeta basic = loadBasicByFileId(s, fileId);
        if (basic == null) {
            return null;
        }
        return getFileContent(s, basic.contentHash);
    }

    /**
     * 通过路径读取完整元数据。
     *
     * <p>元数据按组存储（basic/time/storage/security/ext/tags），该方法会聚合读取并组装为 {@link FileMetadata}。</p>
     *
     * @return 元数据，不存在返回 null
     */
    public FileMetadata getMetadataByPath(String path) throws IOException, InterruptedException, ClassNotFoundException {
        ParsedPath pp = parsePath(null, path);
        Stores s = stores(pp.ns);
        long fileId = getFileIdByPath(s, pp.path);
        if (fileId == 0) {
            return null;
        }
        return loadMetadataByFileId(s, fileId, null);
    }

    /**
     * 递归创建目录（类似 mkdir -p）。
     *
     * <p>会逐级创建 pathToDirId 映射，并把子目录 dirId 以负数 entryId 追加到父目录成员表。</p>
     *
     * @return 最终目录的 dirId
     */
    public long mkdirs(String path) throws IOException {
        ParsedPath pp = parsePath(null, path);
        Stores s = stores(pp.ns);
        return ensureDirPath(s, pp.path);
    }

    /**
     * 分页列出目录成员（entryId 列表）。
     *
     * <p>entryId 的正负由上层约定：通常正数为 fileId，负数为 -dirId。</p>
     *
     * @return entryId 列表；目录不存在返回空列表
     */
    public List<Long> listDir(String path, long offset, int limit) throws IOException {
        ParsedPath pp = parsePath(null, path);
        Stores s = stores(pp.ns);
        long dirId = getDirIdByPath(s, pp.path);
        if (dirId == 0) {
            return new ArrayList<>();
        }
        long[] entries = s.dirStore.listEntries(dirId, offset, limit);
        List<Long> out = new ArrayList<>(entries.length);
        for (long v : entries) {
            out.add(v);
        }
        return out;
    }

    /**
     * 相对路径版本的写文件：以 parentPath 作为基准解析 relativePath。
     *
     * <p>用于避免调用方自行拼接路径，从而统一规范化与禁止目录穿透规则。</p>
     */
    public FileMetadata saveFile(String parentPath, String relativePath, byte[] content, FileMetadata metadata)
            throws IOException, InterruptedException {
        ParsedPath base = parsePath(null, parentPath);
        ParsedPath child = parsePath(base, relativePath);
        Stores s = stores(child.ns);
        ensureDirPath(s, parentPath(child.path));
        long existing = getFileIdByPath(s, child.path);
        long fileId = existing != 0 ? existing : getOrCreateFileIdByPath(s, child.path);
        if (existing == 0) {
            appendFileToDir(s, parentPath(child.path), fileId);
        }
        return saveFileInternal(s, fileId, content, metadata, child.path);
    }

    /**
     * 相对路径版本的 mkdirs：以 parentPath 作为基准解析 relativePath。
     *
     * @return 最终目录的 dirId
     */
    public long mkdirs(String parentPath, String relativePath) throws IOException {
        ParsedPath base = parsePath(null, parentPath);
        ParsedPath child = parsePath(base, relativePath);
        Stores s = stores(child.ns);
        return ensureDirPath(s, child.path);
    }

    private FileMetadata saveFileInternal(Stores s, Long fixedFileId, byte[] content, FileMetadata metadata, String filePath)
            throws IOException, InterruptedException {
        if (content == null) {
            content = new byte[0];
        }
        if (metadata == null) {
            metadata = new FileMetadata();
        }

        byte[] contentHashBytes = DsDataUtil.sha256(content);
        String contentHashStr = toHex(contentHashBytes);

        s.contentStore.update(contentHashBytes, content);

        if (metadata.globalFileId == null || metadata.globalFileId.isEmpty()) {
            metadata.globalFileId = UUID.randomUUID().toString();
        }
        long now = System.currentTimeMillis();
        if (metadata.time.createTime == 0) {
            metadata.time.createTime = now;
        }
        metadata.time.modifyTime = now;
        metadata.time.metaModifyTime = now;
        metadata.basic.fileSize = content.length;
        metadata.storage.fileSizePhysical = content.length;
        metadata.basic.contentHash = contentHashStr;
        metadata.security.hashAlgorithm = "SHA-256";

        long fileId = fixedFileId != null ? fixedFileId : getOrCreateFileIdByGlobalFileId(s, metadata.globalFileId);
        if (filePath != null && !filePath.isEmpty()) {
            metadata.basic.filePathVirtual = filePath;
            metadata.basic.fileName = fileName(filePath);
        }

        saveMetadataGroupByFileId(s, fileId, "basic", metadata.basic);
        saveMetadataGroupByFileId(s, fileId, "time", metadata.time);
        saveMetadataGroupByFileId(s, fileId, "storage", metadata.storage);
        saveMetadataGroupByFileId(s, fileId, "security", metadata.security);
        saveMetadataGroupByFileId(s, fileId, "ext", metadata.ext);
        saveMetadataGroupByFileId(s, fileId, "tags", metadata.tags);

        if (metadata.tags != null && metadata.tags.tags != null) {
            for (Map.Entry<String, String> entry : metadata.tags.tags.entrySet()) {
                String fullTag = entry.getKey() + "=" + entry.getValue();
                s.tagIndex.addTag(fullTag, fileId);
            }
        }

        return metadata;
    }

    /**
     * 按 tag 查询文件（默认 global 命名空间）。
     *
     * <p>tagKey/tagValue 会组合成 "k=v" 作为 tagIndex 的 key；返回的是 BasicMeta 列表。</p>
     */
    public List<FileMetadata.BasicMeta> getFilesByTag(String tagKey, String tagValue)
            throws IOException, InterruptedException, ClassNotFoundException {
        return getFilesByTagInStores(global, tagKey, tagValue);
    }

    /**
     * 通过 globalFileId 读取完整元数据（默认 global 命名空间）。
     */
    public FileMetadata getMetadata(String globalFileId) throws IOException, InterruptedException, ClassNotFoundException {
        long fileId = getFileIdByGlobalFileId(global, globalFileId);
        if (fileId == 0) {
            return null;
        }
        return loadMetadataByFileId(global, fileId, globalFileId);
    }

    /**
     * 通过 globalFileId 读取 basic 元数据组（默认 global 命名空间）。
     */
    public FileMetadata.BasicMeta getBasicMetadata(String globalFileId)
            throws IOException, InterruptedException, ClassNotFoundException {
        long fileId = getFileIdByGlobalFileId(global, globalFileId);
        if (fileId == 0) {
            return null;
        }
        return loadBasicByFileId(global, fileId);
    }

    /**
     * 通过内容哈希（hex 字符串）读取文件内容（默认 global 命名空间）。
     */
    public byte[] getFileContent(String contentHashStr) throws IOException, InterruptedException {
        return getFileContent(global, contentHashStr);
    }

    /**
     * 通过 globalFileId 读取文件内容（默认 global 命名空间）。
     */
    public byte[] getFileContentById(String globalFileId) throws IOException, InterruptedException, ClassNotFoundException {
        FileMetadata.BasicMeta basic = getBasicMetadata(globalFileId);
        if (basic == null) {
            return null;
        }
        return getFileContent(basic.contentHash);
    }

    /**
     * 关闭三套命名空间的底层存储资源（bucket、索引、目录等）。
     */
    public void close() {
        global.close();
        groupPrivate.close();
        system.close();
    }

    private FileMetadata saveFileInternal(Stores s, Long fixedFileId, byte[] content, FileMetadata metadata)
            throws IOException, InterruptedException {
        return saveFileInternal(s, fixedFileId, content, metadata, null);
    }

    private List<FileMetadata.BasicMeta> getFilesByTagInStores(Stores s, String tagKey, String tagValue)
            throws IOException, InterruptedException, ClassNotFoundException {
        List<FileMetadata.BasicMeta> results = new ArrayList<>();
        String fullTag = tagKey + "=" + tagValue;
        List<Long> fileIds = s.tagIndex.getFilesByTag(fullTag);
        for (Long fileId : fileIds) {
            FileMetadata.BasicMeta basic = loadBasicByFileId(s, fileId);
            if (basic != null) {
                results.add(basic);
            }
        }
        return results;
    }

    private long saveMetadataGroupByFileId(Stores s, long fileId, String groupName, Object groupObj) throws IOException, InterruptedException {
        byte[] keyBytes = metaKey(fileId, groupName);
        byte[] metaBytes = serializeObject(groupObj);
        return s.metaStore.update(keyBytes, metaBytes);
    }

    private Object loadMetadataGroupByFileId(Stores s, long fileId, String groupName)
            throws IOException, InterruptedException, ClassNotFoundException {
        byte[] keyBytes = metaKey(fileId, groupName);
        byte[] metaBytes = s.metaStore.get(keyBytes);
        if (metaBytes == null) {
            return null;
        }
        return deserializeObject(metaBytes);
    }

    private FileMetadata.BasicMeta loadBasicByFileId(Stores s, long fileId) throws IOException, InterruptedException, ClassNotFoundException {
        Object obj = loadMetadataGroupByFileId(s, fileId, "basic");
        if (obj instanceof FileMetadata.BasicMeta) {
            return (FileMetadata.BasicMeta) obj;
        }
        return null;
    }

    private FileMetadata loadMetadataByFileId(Stores s, long fileId, String globalFileId)
            throws IOException, InterruptedException, ClassNotFoundException {
        FileMetadata metadata = new FileMetadata();
        metadata.globalFileId = globalFileId;

        FileMetadata.BasicMeta basic = (FileMetadata.BasicMeta) loadMetadataGroupByFileId(s, fileId, "basic");
        if (basic == null) {
            return null;
        }
        metadata.basic = basic;

        FileMetadata.TimeMeta time = (FileMetadata.TimeMeta) loadMetadataGroupByFileId(s, fileId, "time");
        if (time != null) {
            metadata.time = time;
        }

        FileMetadata.StorageMeta storage = (FileMetadata.StorageMeta) loadMetadataGroupByFileId(s, fileId, "storage");
        if (storage != null) {
            metadata.storage = storage;
        }

        FileMetadata.SecurityMeta security = (FileMetadata.SecurityMeta) loadMetadataGroupByFileId(s, fileId, "security");
        if (security != null) {
            metadata.security = security;
        }

        FileMetadata.ExtMeta ext = (FileMetadata.ExtMeta) loadMetadataGroupByFileId(s, fileId, "ext");
        if (ext != null) {
            metadata.ext = ext;
        }

        FileMetadata.TagsMeta tags = (FileMetadata.TagsMeta) loadMetadataGroupByFileId(s, fileId, "tags");
        if (tags != null) {
            metadata.tags = tags;
        }

        return metadata;
    }

    private long getOrCreateFileIdByGlobalFileId(Stores s, String globalFileId) throws IOException {
        byte[] k = globalFileId.getBytes(StandardCharsets.UTF_8);
        byte[] v = s.gidToFileId.get(k);
        long fileId = DsDirectoryStore.bytesToLong(v);
        if (fileId != 0) {
            return fileId;
        }
        fileId = s.dirStore.allocateFileId();
        s.gidToFileId.update(k, DsDirectoryStore.longToBytes(fileId));
        return fileId;
    }

    private long getFileIdByGlobalFileId(Stores s, String globalFileId) throws IOException {
        if (globalFileId == null) {
            return 0;
        }
        byte[] v = s.gidToFileId.get(globalFileId.getBytes(StandardCharsets.UTF_8));
        return DsDirectoryStore.bytesToLong(v);
    }

    private long getOrCreateFileIdByPath(Stores s, String path) throws IOException {
        byte[] k = path.getBytes(StandardCharsets.UTF_8);
        byte[] v = s.pathToFileId.get(k);
        long fileId = DsDirectoryStore.bytesToLong(v);
        if (fileId != 0) {
            return fileId;
        }
        fileId = s.dirStore.allocateFileId();
        s.pathToFileId.update(k, DsDirectoryStore.longToBytes(fileId));
        return fileId;
    }

    private long getFileIdByPath(Stores s, String path) throws IOException {
        byte[] v = s.pathToFileId.get(path.getBytes(StandardCharsets.UTF_8));
        return DsDirectoryStore.bytesToLong(v);
    }

    private long getDirIdByPath(Stores s, String dirPath) throws IOException {
        byte[] v = s.pathToDirId.get(dirPath.getBytes(StandardCharsets.UTF_8));
        return DsDirectoryStore.bytesToLong(v);
    }

    private long ensureDirPath(Stores s, String dirPath) throws IOException {
        String p = DsPathUtil.normalizeLinuxPath(dirPath, true);
        if (p.equals("/")) {
            byte[] v = s.pathToDirId.get("/".getBytes(StandardCharsets.UTF_8));
            long rootId = DsDirectoryStore.bytesToLong(v);
            if (rootId != 0) {
                return rootId;
            }
            rootId = s.dirStore.allocateDirId();
            s.pathToDirId.update("/".getBytes(StandardCharsets.UTF_8), DsDirectoryStore.longToBytes(rootId));
            return rootId;
        }

        ensureDirPath(s, "/");
        String[] segs = p.substring(1).split("/", -1);
        String current = "";
        long parentId = getDirIdByPath(s, "/");
        for (String seg : segs) {
            current = current + "/" + seg;
            long dirId = getDirIdByPath(s, current);
            if (dirId == 0) {
                dirId = s.dirStore.allocateDirId();
                s.pathToDirId.update(current.getBytes(StandardCharsets.UTF_8), DsDirectoryStore.longToBytes(dirId));
                s.dirStore.appendEntry(parentId, -dirId);
            }
            parentId = dirId;
        }
        return parentId;
    }

    private void appendFileToDir(Stores s, String parentDirPath, long fileId) throws IOException {
        long parentId = ensureDirPath(s, parentDirPath);
        s.dirStore.appendEntry(parentId, fileId);
    }

    private static String parentPath(String path) {
        String p = DsPathUtil.normalizeLinuxPath(path, true);
        if (p.equals("/")) {
            return "/";
        }
        int idx = p.lastIndexOf('/');
        if (idx <= 0) {
            return "/";
        }
        return p.substring(0, idx);
    }

    private static String fileName(String path) {
        String p = DsPathUtil.normalizeLinuxPath(path, true);
        if (p.equals("/")) {
            return "";
        }
        int idx = p.lastIndexOf('/');
        return idx >= 0 ? p.substring(idx + 1) : p;
    }

    private static byte[] metaKey(long fileId, String groupName) {
        String compositeKey = "fid:" + fileId + "_" + groupName;
        return compositeKey.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] getFileContent(Stores s, String contentHashStr) throws IOException, InterruptedException {
        byte[] contentHashBytes = hexStringToByteArray(contentHashStr);
        return s.contentStore.get(contentHashBytes);
    }

    private Stores stores(String ns) {
        if (NS_SYSTEM.equals(ns)) {
            return system;
        }
        if (NS_GROUP_PRIVATE.equals(ns)) {
            return groupPrivate;
        }
        return global;
    }

    private static ParsedPath parsePath(ParsedPath parent, String path) {
        if (path == null) {
            throw new IllegalArgumentException("path cannot be null");
        }
        if (path.startsWith("$")) {
            if (path.length() == 1 || path.charAt(1) != '/') {
                throw new IllegalArgumentException("system path must start with '$/'");
            }
            String normalized = DsPathUtil.normalizeLinuxPath(path.substring(1), true);
            return new ParsedPath(NS_SYSTEM, normalized);
        }
        if (path.startsWith("/")) {
            String normalized = DsPathUtil.normalizeLinuxPath(path, true);
            if (normalized.startsWith("/group/") || normalized.equals("/group")) {
                return new ParsedPath(NS_GROUP_PRIVATE, normalized);
            }
            if (normalized.startsWith("/private/") || normalized.equals("/private")) {
                return new ParsedPath(NS_GROUP_PRIVATE, normalized);
            }
            return new ParsedPath(NS_GLOBAL, normalized);
        }
        if (parent == null) {
            throw new IllegalArgumentException("relative path requires parentPath");
        }
        String normalized = DsPathUtil.resolveLinuxPath(parent.path, path);
        return new ParsedPath(parent.ns, normalized);
    }

    private static final class ParsedPath {
        final String ns;
        final String path;

        ParsedPath(String ns, String path) {
            this.ns = ns;
            this.path = path;
        }
    }

    private static final class Stores {
        final DsSha256KV contentStore;
        final DsSha256KV metaStore;
        final DsSha256KV gidToFileId;
        final DsSha256KV pathToFileId;
        final DsSha256KV pathToDirId;
        final DsTagIndex tagIndex;
        final DsDirectoryStore dirStore;

        Stores(String rootDir, String nsDir) throws IOException {
            String base = rootDir + File.separator + nsDir;
            this.contentStore = new DsSha256KV(base + File.separator + "content");
            this.metaStore = new DsSha256KV(base + File.separator + "meta");
            this.gidToFileId = new DsSha256KV(base + File.separator + "gid_map");
            this.pathToFileId = new DsSha256KV(base + File.separator + "path_map");
            this.pathToDirId = new DsSha256KV(base + File.separator + "dir_map");
            this.tagIndex = new DsTagIndex(base + File.separator + "tags");
            this.dirStore = new DsDirectoryStore(base + File.separator + "dir_blocks");
        }

        void close() {
            contentStore.close();
            metaStore.close();
            gidToFileId.close();
            pathToFileId.close();
            pathToDirId.close();
            tagIndex.close();
            try {
                dirStore.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static byte[] serializeObject(Object obj) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
            return bos.toByteArray();
        }
    }

    private static Object deserializeObject(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes); ObjectInputStream ois = new ObjectInputStream(bis)) {
            return ois.readObject();
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 把 hex 字符串解码为 byte[]。
     *
     * <p>要求输入长度为偶数，且字符集为 [0-9a-fA-F]。</p>
     */
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int d1 = Character.digit(s.charAt(i), 16);
            int d2 = Character.digit(s.charAt(i + 1), 16);
            data[i / 2] = (byte) ((d1 << 4) + d2);
        }
        return data;
    }
}
