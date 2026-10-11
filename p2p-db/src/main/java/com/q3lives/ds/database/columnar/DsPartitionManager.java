package com.q3lives.ds.database.columnar;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.database.DsTableLayout;

/**
 * 本地节点分区管理器。
 *
 * <p>为每个本地分区维护一个独立的 {@link ColumnarStore} 实例，使列 map、列组 map、
 * valueStore、bucketStore 都按 rowId 的低 8 位落到对应分区目录。</p>
 *
 * <p>目录规则：
 * <ul>
 *   <li>未指定多目录时，所有分区落在 {@code nodeHome/partitions/<nnn>/}；</li>
 *   <li>指定多目录时，分区按轮询分散到各目录，格式为 {@code <dir>/partitions/<nnn>/}。</li>
 * </ul>
 * </p>
 */
public final class DsPartitionManager implements AutoCloseable {

    private final File nodeHome;
    private final int partitionCount;
    private final List<File> partitionRoots;
    private final ColumnarStore[] stores;

    /**
     * 根据 ORM 布局创建分区管理器。
     *
     * @param nodeHome 节点主目录
     * @param layout   表布局（用于取分区数与多目录配置）
     */
    public DsPartitionManager(File nodeHome, DsTableLayout layout) {
        this(nodeHome,
                layout == null ? DsPartitionRouter.maxPartitionCount() : layout.getPartitionCount(),
                layout == null ? new String[0] : layout.getDirectories());
    }

    /**
     * 显式指定分区数与多目录创建分区管理器。
     *
     * @param nodeHome       节点主目录
     * @param partitionCount 分区数，必须大于 0 且不超过 256
     * @param directories    多目录绑定，空则全部落在 nodeHome
     */
    public DsPartitionManager(File nodeHome, int partitionCount, String[] directories) {
        if (nodeHome == null) {
            throw new NullPointerException("nodeHome");
        }
        if (partitionCount <= 0 || partitionCount > DsPartitionRouter.maxPartitionCount()) {
            throw new IllegalArgumentException(
                    "partitionCount out of range: " + partitionCount);
        }
        this.nodeHome = nodeHome;
        this.partitionCount = partitionCount;
        this.partitionRoots = buildPartitionRoots(nodeHome, partitionCount, directories);
        this.stores = new ColumnarStore[partitionCount];
    }

    private static List<File> buildPartitionRoots(File nodeHome, int count, String[] directories) {
        List<File> bases;
        if (directories == null || directories.length == 0) {
            bases = Collections.nCopies(count, nodeHome);
        } else {
            bases = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                File base = new File(directories[i % directories.length]);
                if (!base.isAbsolute()) {
                    base = new File(nodeHome, base.getPath());
                }
                bases.add(base);
            }
        }

        List<File> roots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            File root = new File(bases.get(i), "partitions/" + String.format("%03d", i));
            if (!root.exists()) {
                root.mkdirs();
            }
            roots.add(root);
        }
        return roots;
    }

    /**
     * 返回 rowId 对应分区的 ColumnarStore。
     */
    public ColumnarStore getStore(long rowId) {
        return getStore(DsPartitionRouter.partitionId(rowId));
    }

    /**
     * 返回指定分区的 ColumnarStore（懒加载）。
     */
    public ColumnarStore getStore(int partitionId) {
        if (partitionId < 0 || partitionId >= partitionCount) {
            throw new IllegalArgumentException("partitionId out of range: " + partitionId);
        }
        ColumnarStore store = stores[partitionId];
        if (store == null) {
            synchronized (this) {
                store = stores[partitionId];
                if (store == null) {
                    store = new ColumnarStore(partitionRoots.get(partitionId));
                    stores[partitionId] = store;
                }
            }
        }
        return store;
    }

    public int getPartitionCount() {
        return partitionCount;
    }

    /**
     * 返回指定分区是否已初始化（被访问过）。
     *
     * <p>不会触发懒加载创建 ColumnarStore。</p>
     */
    public boolean isPartitionInitialized(int partitionId) {
        if (partitionId < 0 || partitionId >= partitionCount) {
            return false;
        }
        return stores[partitionId] != null;
    }

    public File getPartitionRoot(int partitionId) {
        if (partitionId < 0 || partitionId >= partitionCount) {
            throw new IllegalArgumentException("partitionId out of range: " + partitionId);
        }
        return partitionRoots.get(partitionId);
    }

    public File getNodeHome() {
        return nodeHome;
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (int i = 0; i < stores.length; i++) {
            ColumnarStore store = stores[i];
            if (store == null) {
                continue;
            }
            try {
                store.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
