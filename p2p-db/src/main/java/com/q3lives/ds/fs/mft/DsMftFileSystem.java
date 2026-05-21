package com.q3lives.ds.fs.mft;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.core.DsString;
import com.q3lives.ds.fs.Ds128Inode;
import com.q3lives.ds.fs.Ds128SuperInode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 MFT（DsMftInodeTable）的传统寻址文件系统。
 *
 * <p>核心设计：</p>
 * <ul>
 *   <li>使用固定 128B slot 的 MFT 管理 inode，fileId 即数组下标</li>
 *   <li>文件内容走 DsFixedBucketStore，fileId -> bucketId 映射由 DsHashMap 管理</li>
 *   <li>目录内容走 DsMftDirStore（4K->64K->64M 分级），条目只存 fileId，名称通过 inode 获取</li>
 *   <li>空文件在映射中无记录，仅 inode.data_size = 0</li>
 *   <li>短文件名（<=31 字节）直接存入 inode.name，长文件名走 DsString</li>
 *   <li>路径解析从根目录层层向下（传统 Unix 方式）</li>
 *   <li>writeFile 不会自动创建父目录，父目录不存在抛异常</li>
 *   <li>支持全局配置：atime 记录、审计日志、文件系统名称存入 SuperInode</li>
 * </ul>
 *
 * <p>存储布局（mftDir/）：</p>
 * <pre>
 * mft.dat              DsMftInodeTable
 * free_file_ids.set    空闲 fileId 集合
 * file_to_bucket.map   DsHashMap: fileId -> bucketId（文件内容）
 * file_to_name.map     DsHashMap: fileId -> nameId（长文件名）
 * dir_blocks/          DsMftDirStore: 目录条目表（4K->64K->64M 分级）
 * buckets/             DsFixedBucketStore: 文件内容
 * names/               DsString: 长文件名存储
 * atime.map            DsHashMap: fileId -> atime（访问时间）
 * </pre>
 */
public class DsMftFileSystem implements AutoCloseable {

    private static final long ROOT_FILE_ID = 1L;
    private static final short MODE_FILE = (short) 0x81A4;   // 常规文件 rw-r--r--
    private static final short MODE_DIR = (short) 0x41ED;    // 目录 rwxr-xr-x

    private final Path mftDir;
    private final DsMftInodeTable mft;
    private final DsFixedBucketStore bucketStore;
    private final DsMftDirStore dirStore;
    private final DsHashMap fileToBucket;
    private final DsHashMap fileToName;
    private final DsString nameStore;
    private final DsHashMap atimeMap;
    private final ReentrantLock lock = new ReentrantLock();
    private final DsMftFileSystemConfig config;
    private final Logger auditLogger;

    /**
     * 目录项。
     */
    public static final class DirEntry {
        public final String name;
        public final long fileId;
        public final boolean isDirectory;

        public DirEntry(String name, long fileId, boolean isDirectory) {
            this.name = name;
            this.fileId = fileId;
            this.isDirectory = isDirectory;
        }
    }

    // ================== 工厂方法 ==================

    /**
     * 从 YAML 配置文件加载并初始化文件系统。
     *
     * <p>如果文件系统尚未创建，则按配置初始化（根目录、命名空间目录、文件系统名称等）。
     * 如果已存在，则加载现有文件系统并校验配置一致性。</p>
     *
     * @param configPath YAML 配置文件路径
     * @return 立即可用的文件系统实例
     */
    public static DsMftFileSystem loadOrInit(Path configPath) throws IOException {
        DsMftFileSystemConfigLoader.LoadedConfig loaded =
                DsMftFileSystemConfigLoader.loadFromPath(configPath);
        return loadOrInit(loaded.config);
    }

    /**
     * 从配置对象加载或初始化文件系统。
     *
     * @param config 文件系统配置
     * @return 立即可用的文件系统实例
     */
    public static DsMftFileSystem loadOrInit(DsMftFileSystemConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        Path mftDir = Path.of(config.getNamespaceDir());
        Files.createDirectories(mftDir);
        return new DsMftFileSystem(mftDir, config);
    }

    // ================== 构造方法 ==================

    /**
     * 打开或创建文件系统（无配置，使用默认行为）。
     *
     * @param mftDir MFT 存储目录
     */
    public DsMftFileSystem(Path mftDir) throws IOException {
        this(mftDir, new DsMftFileSystemConfig());
    }

    /**
     * 打开或创建文件系统（带配置）。
     *
     * @param mftDir MFT 存储目录
     * @param config 全局配置
     */
    public DsMftFileSystem(Path mftDir, DsMftFileSystemConfig config) throws IOException {
        this.mftDir = mftDir.toAbsolutePath().normalize();
        this.config = config != null ? config : new DsMftFileSystemConfig();
        this.auditLogger = LoggerFactory.getLogger(
                DsMftFileSystem.class.getName() + ".audit." + this.mftDir.getFileName());
        this.mft = new DsMftInodeTable(this.mftDir, 64);
        this.bucketStore = new DsFixedBucketStore(this.mftDir.resolve("buckets").toString());
        this.dirStore = new DsMftDirStore(this.mftDir.resolve("dir_blocks").toString());
        this.fileToBucket = new DsHashMap(this.mftDir.resolve("file_to_bucket.map").toFile());
        this.fileToName = new DsHashMap(this.mftDir.resolve("file_to_name.map").toFile());
        this.nameStore = new DsString(this.mftDir.resolve("names").toString());
        this.atimeMap = new DsHashMap(this.mftDir.resolve("atime.map").toFile());

        boolean isNew = !mft.isAllocated(ROOT_FILE_ID);
        initRootDir();

        if (isNew) {
            // 新文件系统：写入文件系统名称、初始化命名空间目录
            initFsName(this.config.getFsName());
            initNamespaceDirs();
        } else {
            // 已有文件系统：校验文件系统名称（如配置中指定了）
            String existingName = readFsName();
            if (this.config.getFsName() != null && !this.config.getFsName().isBlank()
                    && !this.config.getFsName().equals(existingName)) {
                // 允许更新文件系统名称
                initFsName(this.config.getFsName());
            }
        }
    }

    // ================== 文件操作 ==================

    /**
     * 读取文件内容。
     *
     * @param path 绝对路径
     * @return 文件内容；不存在或不是文件返回 null；空文件返回空数组
     */
    public byte[] readFile(String path) throws IOException {
        lock.lock();
        try {
            long fileId = resolvePath(path);
            if (fileId == 0) {
                return null;
            }
            Ds128Inode inode = mft.readInode(fileId);
            if (inode == null || !isFile(inode)) {
                return null;
            }
            updateAtime(fileId);
            return readContent(fileId, inode);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 写入文件内容（不存在则创建，存在则覆盖）。
     * 父目录必须已存在，否则抛出 IOException。
     *
     * @param path 绝对路径
     * @param data 文件内容（可为 null，视为空文件）
     */
    public void writeFile(String path, byte[] data) throws IOException {
        if (data == null) {
            data = new byte[0];
        }
        lock.lock();
        try {
            String[] parts = splitPath(normalizePath(path));
            if (parts.length == 0) {
                throw new IllegalArgumentException("cannot write to root directory");
            }
            String fileName = parts[parts.length - 1];
            String parentPath = joinPath(parts, 0, parts.length - 1);

            long parentId = resolvePath(parentPath);
            if (parentId == 0) {
                audit("WRITE", path, false);
                throw new IOException("parent directory does not exist: " + parentPath);
            }
            Ds128Inode parentInode = mft.readInode(parentId);
            if (parentInode == null || !isDirectory(parentInode)) {
                audit("WRITE", path, false);
                throw new IOException("not a directory: " + parentPath);
            }

            long fileId = findInDir(parentId, fileName);
            boolean isNew = fileId == 0;
            if (isNew) {
                fileId = mft.allocateInode();
            }

            Ds128Inode inode = mft.readInode(fileId);
            if (inode == null) {
                inode = new Ds128Inode();
                inode.ref_count = 1;
            }
            inode.i_mode = MODE_FILE;
            setInodeName(fileId, inode, fileName);
            inode.inode_parent = parentId;

            // 写入内容
            writeContent(fileId, inode, data);
            inode.data_mtime = System.currentTimeMillis();

            mft.writeInode(fileId, inode);

            if (isNew) {
                long storeDirId = getOrCreateStoreDirId(parentId);
                dirStore.appendEntry(storeDirId, fileId, nameHash(fileName), MODE_FILE);
                parentInode.data_size++;
                mft.writeInode(parentId, parentInode);
            }
            audit("WRITE", path, true);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 删除文件。
     *
     * @param path 绝对路径
     * @return true 如果文件存在并被删除
     */
    public boolean deleteFile(String path) throws IOException {
        lock.lock();
        try {
            String[] parts = splitPath(normalizePath(path));
            if (parts.length == 0) {
                throw new IllegalArgumentException("cannot delete root directory");
            }
            String fileName = parts[parts.length - 1];
            String parentPath = joinPath(parts, 0, parts.length - 1);
            long parentId = resolvePath(parentPath);
            if (parentId == 0) {
                audit("DELETE_FILE", path, false);
                return false;
            }

            long fileId = findInDir(parentId, fileName);
            if (fileId == 0) {
                audit("DELETE_FILE", path, false);
                return false;
            }

            Ds128Inode inode = mft.readInode(fileId);
            if (inode == null || !isFile(inode)) {
                audit("DELETE_FILE", path, false);
                return false;
            }

            // 删除内容和映射
            deleteContent(fileId, inode);
            deleteName(fileId, inode);
            mft.freeInode(fileId);

            // 更新父目录
            Long storeDirId = fileToBucket.get(parentId);
            if (storeDirId != null) {
                dirStore.removeEntry(storeDirId, fileId);
                Ds128Inode parentInode = mft.readInode(parentId);
                if (parentInode != null) {
                    parentInode.data_size--;
                    if (parentInode.data_size <= 0) {
                        parentInode.data_size = 0;
                        releaseStoreDirId(parentId, storeDirId);
                    }
                    mft.writeInode(parentId, parentInode);
                }
            }
            audit("DELETE_FILE", path, true);
            return true;
        } finally {
            lock.unlock();
        }
    }

    // ================== 目录操作 ==================

    /**
     * 创建目录（类似 mkdir -p，逐级创建不存在的父目录）。
     *
     * @param path 绝对路径
     */
    public void mkdir(String path) throws IOException {
        lock.lock();
        try {
            ensureDirPath(normalizePath(path));
            audit("MKDIR", path, true);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 列出目录成员。
     *
     * @param path 绝对路径
     * @return 目录项列表；不是目录或不存在返回空列表
     */
    public List<DirEntry> listDir(String path) throws IOException {
        lock.lock();
        try {
            long dirId = resolvePath(normalizePath(path));
            if (dirId == 0) {
                return Collections.emptyList();
            }
            Ds128Inode inode = mft.readInode(dirId);
            if (inode == null || !isDirectory(inode)) {
                return Collections.emptyList();
            }
            // data_size == 0 表示空目录，快速返回，无需读取映射
            if (inode.data_size == 0) {
                return Collections.emptyList();
            }

            Long storeDirId = fileToBucket.get(dirId);
            if (storeDirId == null) {
                return Collections.emptyList();
            }

            long total = dirStore.size(storeDirId);
            List<DirEntry> result = new ArrayList<>((int) Math.min(total, 1024));
            long offset = 0;
            while (offset < total) {
                int limit = (int) Math.min(total - offset, 1024);
                DsMftDirStore.Entry[] entries = dirStore.listEntries(storeDirId, offset, limit);
                for (DsMftDirStore.Entry e : entries) {
                    if (e == null || e.fileId == 0) continue;
                    Ds128Inode childInode = mft.readInode(e.fileId);
                    if (childInode != null) {
                        String name = getInodeName(e.fileId, childInode);
                        boolean isDir = e.isDirectory();
                        result.add(new DirEntry(name, e.fileId, isDir));
                    }
                }
                offset += entries.length;
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 删除空目录。
     *
     * @param path 绝对路径
     * @return true 如果目录存在且为空并被删除
     */
    public boolean deleteDir(String path) throws IOException {
        lock.lock();
        try {
            String[] parts = splitPath(normalizePath(path));
            if (parts.length == 0) {
                throw new IllegalArgumentException("cannot delete root directory");
            }
            String dirName = parts[parts.length - 1];
            String parentPath = joinPath(parts, 0, parts.length - 1);
            long parentId = resolvePath(parentPath);
            if (parentId == 0) {
                audit("DELETE_DIR", path, false);
                return false;
            }

            long dirId = findInDir(parentId, dirName);
            if (dirId == 0) {
                audit("DELETE_DIR", path, false);
                return false;
            }

            Ds128Inode inode = mft.readInode(dirId);
            if (inode == null || !isDirectory(inode)) {
                audit("DELETE_DIR", path, false);
                return false;
            }

            // 检查是否为空（data_size == 0 表示无条目）
            if (inode.data_size > 0) {
                audit("DELETE_DIR", path, false);
                return false;
            }

            // 释放目录的 storeDirId（如果有）
            Long storeDirId = fileToBucket.get(dirId);
            if (storeDirId != null) {
                releaseStoreDirId(dirId, storeDirId);
            }
            deleteName(dirId, inode);
            mft.freeInode(dirId);

            // 更新父目录
            Long parentStoreDirId = fileToBucket.get(parentId);
            if (parentStoreDirId != null) {
                dirStore.removeEntry(parentStoreDirId, dirId);
                Ds128Inode parentInode = mft.readInode(parentId);
                if (parentInode != null) {
                    parentInode.data_size--;
                    if (parentInode.data_size <= 0) {
                        parentInode.data_size = 0;
                        releaseStoreDirId(parentId, parentStoreDirId);
                    }
                    mft.writeInode(parentId, parentInode);
                }
            }
            audit("DELETE_DIR", path, true);
            return true;
        } finally {
            lock.unlock();
        }
    }

    // ================== 元数据查询 ==================

    /**
     * 查询文件/目录的 inode。
     *
     * @param path 绝对路径
     * @return inode；不存在返回 null
     */
    public Ds128Inode stat(String path) throws IOException {
        lock.lock();
        try {
            long fileId = resolvePath(normalizePath(path));
            if (fileId == 0) {
                return null;
            }
            Ds128Inode inode = mft.readInode(fileId);
            if (inode != null && isFile(inode)) {
                updateAtime(fileId);
            }
            return inode;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 判断路径是否存在。
     */
    public boolean exists(String path) throws IOException {
        return stat(path) != null;
    }

    /**
     * 获取文件系统名称（从 SuperInode 读取）。
     *
     * @return 文件系统名称；未设置返回空字符串
     */
    public String getFsName() throws IOException {
        lock.lock();
        try {
            return readFsName();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取当前配置。
     */
    public DsMftFileSystemConfig getConfig() {
        return config;
    }

    // ================== 内部方法 ==================

    private static int nameHash(String name) {
        return name.hashCode();
    }

    /**
     * 获取目录的 storeDirId，不存在则按需创建。
     */
    private long getOrCreateStoreDirId(long dirId) throws IOException {
        Long storeDirId = fileToBucket.get(dirId);
        if (storeDirId == null) {
            Ds128Inode inode = mft.readInode(dirId);
            long parentId = (inode != null) ? inode.inode_parent : 0L;
            storeDirId = dirStore.createDir(parentId);
            fileToBucket.put(dirId, storeDirId.longValue());
        }
        return storeDirId;
    }

    /**
     * 释放目录的 storeDirId，清空映射。
     */
    private void releaseStoreDirId(long dirId, long storeDirId) throws IOException {
        dirStore.removeDir(storeDirId);
        fileToBucket.remove(dirId);
    }

    private void initRootDir() throws IOException {
        if (!mft.isAllocated(ROOT_FILE_ID)) {
            long rootId = mft.allocateInode();
            if (rootId != ROOT_FILE_ID) {
                throw new IllegalStateException("root fileId should be 1, got: " + rootId);
            }
            Ds128Inode root = new Ds128Inode();
            root.ref_count = 1;
            root.i_mode = MODE_DIR;
            root.name = new byte[32];
            root.name[0] = 1;
            root.name[1] = '/';
            root.inode_parent = 0;
            root.data_size = 0;
            mft.writeInode(rootId, root);
        }
    }

    // ----- 文件系统名称管理（存入 SuperInode） -----

    private void initFsName(String fsName) {
        if (fsName == null || fsName.isBlank()) {
            return;
        }
        Ds128SuperInode sup = mft.readSuperInode();
        byte[] nameBytes = fsName.getBytes(StandardCharsets.UTF_8);
        sup.name = new byte[32];
        int len = Math.min(nameBytes.length, 31);
        sup.name[0] = (byte) len;
        System.arraycopy(nameBytes, 0, sup.name, 1, len);
        mft.writeSuperInode(sup);
    }

    private String readFsName() {
        Ds128SuperInode sup = mft.readSuperInode();
        if (sup.name == null || sup.name.length == 0 || sup.name[0] <= 0) {
            return "";
        }
        int len = sup.name[0] & 0xFF;
        if (len > 31) {
            len = 31;
        }
        return new String(sup.name, 1, len, StandardCharsets.UTF_8);
    }

    // ----- atime 管理 -----

    /**
     * 更新访问时间（atime）。
     * 当配置开启 atime 时，将时间戳存入独立的 atime.map（DsHashMap: fileId -> atime）。
     */
    private void updateAtime(long fileId) throws IOException {
        if (!config.isAtimeEnabled()) {
            return;
        }
        atimeMap.put(fileId, System.currentTimeMillis());
    }

    /**
     * 查询文件的最近访问时间（atime）。
     *
     * @param path 文件路径
     * @return atime 时间戳；文件不存在或 atime 未记录返回 0
     */
    public long getAtime(String path) throws IOException {
        lock.lock();
        try {
            long fileId = resolvePath(normalizePath(path));
            if (fileId == 0) {
                return 0L;
            }
            Ds128Inode inode = mft.readInode(fileId);
            if (inode == null || !isFile(inode)) {
                return 0L;
            }
            Long atime = atimeMap.get(fileId);
            return atime != null ? atime : 0L;
        } finally {
            lock.unlock();
        }
    }

    // ----- 审计日志 -----

    private void audit(String operation, String path, boolean success) {
        if (!config.isAuditLogEnabled()) {
            return;
        }
        auditLogger.info("[{}] path={} success={}", operation, path, success);
    }

    // ----- 命名空间目录初始化 -----

    private void initNamespaceDirs() throws IOException {
        List<String> dirs = config.getNamespaceDirs();
        if (dirs == null || dirs.isEmpty()) {
            return;
        }
        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            String normalized = normalizePath(dir);
            if (normalized.equals("/")) {
                continue;
            }
            ensureDirPath(normalized);
        }
    }

    // ----- 路径解析 -----

    /**
     * 路径解析：从根目录层层查找，返回最终 fileId；未找到返回 0。
     */
    private long resolvePath(String path) throws IOException {
        String[] parts = splitPath(path);
        long dirId = ROOT_FILE_ID;
        for (int i = 1; i < parts.length; i++) {
            String name = parts[i];
            if (name.isEmpty()) {
                continue;
            }
            long fileId = findInDir(dirId, name);
            if (fileId == 0) {
                return 0;
            }
            dirId = fileId;
        }
        return dirId;
    }

    /**
     * 在指定目录中按名称查找 fileId，未找到返回 0。
     */
    private long findInDir(long dirId, String name) throws IOException {
        Ds128Inode dirInode = mft.readInode(dirId);
        if (dirInode == null || dirInode.data_size == 0) {
            return 0;
        }
        Long storeDirId = fileToBucket.get(dirId);
        if (storeDirId == null) {
            return 0;
        }
        int targetHash = nameHash(name);
        long total = dirStore.size(storeDirId);
        long offset = 0;
        while (offset < total) {
            int limit = (int) Math.min(total - offset, 1024);
            DsMftDirStore.Entry[] entries = dirStore.listEntries(storeDirId, offset, limit);
            for (DsMftDirStore.Entry e : entries) {
                if (e == null || e.fileId == 0) continue;
                if (e.nameHash != targetHash) continue;
                Ds128Inode inode = mft.readInode(e.fileId);
                if (inode != null) {
                    String childName = getInodeName(e.fileId, inode);
                    if (name.equals(childName)) {
                        return e.fileId;
                    }
                }
            }
            offset += entries.length;
        }
        return 0;
    }

    /**
     * 确保路径上的所有目录都存在，返回最终目录的 fileId。
     */
    private long ensureDirPath(String path) throws IOException {
        String[] parts = splitPath(path);
        long parentId = ROOT_FILE_ID;
        for (int i = 1; i < parts.length; i++) {
            String name = parts[i];
            if (name.isEmpty()) {
                continue;
            }
            long dirId = findInDir(parentId, name);
            if (dirId == 0) {
                dirId = createDir(parentId, name);
            }
            parentId = dirId;
        }
        return parentId;
    }

    private long createDir(long parentId, String name) throws IOException {
        long dirId = mft.allocateInode();
        Ds128Inode inode = new Ds128Inode();
        inode.ref_count = 1;
        inode.i_mode = MODE_DIR;
        setInodeName(dirId, inode, name);
        inode.inode_parent = parentId;
        inode.data_size = 0;
        inode.inode_ctime = System.currentTimeMillis();
        inode.inode_mtime = System.currentTimeMillis();
        mft.writeInode(dirId, inode);

        // 按需分配父目录的 storeDirId 并添加条目
        long parentStoreDirId = getOrCreateStoreDirId(parentId);
        dirStore.appendEntry(parentStoreDirId, dirId, nameHash(name), MODE_DIR);
        Ds128Inode parentInode = mft.readInode(parentId);
        if (parentInode != null) {
            parentInode.data_size++;
            mft.writeInode(parentId, parentInode);
        }
        return dirId;
    }

    // ----- 文件内容读写（fileId -> bucketId 映射层） -----

    private byte[] readContent(long fileId, Ds128Inode inode) throws IOException {
        if (inode.data_size == 0) {
            return new byte[0];
        }
        Long bucketId = fileToBucket.get(fileId);
        if (bucketId == null) {
            return new byte[0];
        }
        return bucketStore.get("mft", "content", bucketId, (int) inode.data_size);
    }

    private void writeContent(long fileId, Ds128Inode inode, byte[] data) throws IOException {
        Long oldBucketId = fileToBucket.get(fileId);
        if (data.length == 0) {
            if (oldBucketId != null) {
                bucketStore.remove("mft", "content", oldBucketId);
                fileToBucket.remove(fileId);
            }
            inode.data_size = 0;
            return;
        }
        long newBucketId;
        if (oldBucketId == null) {
            newBucketId = bucketStore.put("mft", "content", data);
        } else {
            newBucketId = bucketStore.update("mft", "content", oldBucketId, data,
                    DsFixedBucketStore.UpdatePolicy.SHRINK_TO_FIT);
        }
        if (oldBucketId != null && oldBucketId != newBucketId) {
            bucketStore.remove("mft", "content", oldBucketId);
        }
        fileToBucket.put(fileId, newBucketId);
        inode.data_size = data.length;
    }

    private void deleteContent(long fileId, Ds128Inode inode) throws IOException {
        Long bucketId = fileToBucket.get(fileId);
        if (bucketId != null) {
            bucketStore.remove("mft", "content", bucketId);
            fileToBucket.remove(fileId);
        }
        inode.data_size = 0;
    }

    // ----- 文件名管理 -----

    private String getInodeName(long fileId, Ds128Inode inode) throws IOException {
        if (inode.name[0] > 0) {
            int len = inode.name[0] & 0xFF;
            if (len > 31) {
                len = 31;
            }
            return new String(inode.name, 1, len, StandardCharsets.UTF_8);
        }
        Long nameId = fileToName.get(fileId);
        if (nameId != null) {
            return nameStore.get(nameId);
        }
        return "";
    }

    private void setInodeName(long fileId, Ds128Inode inode, String name) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length <= 31) {
            inode.name = new byte[32];
            inode.name[0] = (byte) nameBytes.length;
            System.arraycopy(nameBytes, 0, inode.name, 1, nameBytes.length);
            Long oldNameId = fileToName.remove(fileId);
            if (oldNameId != null) {
                nameStore.remove(oldNameId);
            }
        } else {
            inode.name = new byte[32];
            inode.name[0] = 0;
            long nameId = nameStore.add(name);
            Long oldNameId = fileToName.put(fileId, nameId);
            if (oldNameId != null && oldNameId != nameId) {
                nameStore.remove(oldNameId);
            }
        }
    }

    private void deleteName(long fileId, Ds128Inode inode) throws IOException {
        if (inode.name[0] == 0) {
            Long nameId = fileToName.remove(fileId);
            if (nameId != null) {
                nameStore.remove(nameId);
            }
        }
    }

    // ----- 路径工具 -----

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String p = path.replace('\\', '/');
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        StringBuilder sb = new StringBuilder();
        boolean lastWasSlash = false;
        for (char c : p.toCharArray()) {
            if (c == '/') {
                if (!lastWasSlash) {
                    sb.append(c);
                    lastWasSlash = true;
                }
            } else {
                sb.append(c);
                lastWasSlash = false;
            }
        }
        p = sb.toString();
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String[] splitPath(String path) {
        return path.split("/", -1);
    }

    private static String joinPath(String[] parts, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append("/").append(parts[i]);
        }
        if (sb.length() == 0) {
            return "/";
        }
        return sb.toString();
    }

    private static boolean isFile(Ds128Inode inode) {
        return (inode.i_mode & 0x8000) != 0;
    }

    private static boolean isDirectory(Ds128Inode inode) {
        return (inode.i_mode & 0x4000) != 0;
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            if (atimeMap != null) {
                atimeMap.close();
            }
            if (nameStore != null) {
                nameStore.close();
            }
            if (fileToName != null) {
                fileToName.close();
            }
            if (fileToBucket != null) {
                fileToBucket.close();
            }
            if (dirStore != null) {
                try {
                    dirStore.close();
                } catch (IOException ignored) {
                }
            }
            if (bucketStore != null) {
                bucketStore.close();
            }
            if (mft != null) {
                mft.close();
            }
        } finally {
            lock.unlock();
        }
    }
}
