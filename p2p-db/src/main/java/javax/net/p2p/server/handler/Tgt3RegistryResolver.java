package javax.net.p2p.server.handler;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.registry.DsRegistry;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

public final class Tgt3RegistryResolver {

    private static final ConcurrentHashMap<String, DsRegistry> INSTANCES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DsRegistry> TEST_INJECTS = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, DsDatabaseLocal> ORM_INSTANCES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DsDatabaseLocal> ORM_TEST_INJECTS = new ConcurrentHashMap<>();

    private Tgt3RegistryResolver() {}

    static int[] resolveTarget(int ts, int tp, long keyGlobalNodeId) {
        if (ts >= 0 && tp >= 0) return new int[]{ts, tp};
        if (keyGlobalNodeId != 0L) {
            int s = DsIdLayout.serverId(keyGlobalNodeId);
            int p = DsIdLayout.partitionId(keyGlobalNodeId);
            return new int[]{s, p};
        }
        throw new IllegalArgumentException("双模式路由失败：targetServerId/PartitionId=-1 且 keyGlobalNodeId=0，无法解 target (srv,pid)");
    }

    public static int[] resolveOrmTarget(int ts, int tp, long globalRowId) {
        return resolveTarget(ts, tp, globalRowId);
    }

    static String defaultBaseRoot() {
        String base = System.getProperty("p2p.registry.home");
        if (base == null || base.isEmpty()) {
            base = new File(System.getProperty("user.home"), ".p2p/registry").getAbsolutePath();
        }
        return base;
    }

    static String defaultOrmBaseRoot() {
        String base = System.getProperty("p2p.orm.home");
        if (base == null || base.isEmpty()) {
            base = new File(System.getProperty("user.home"), ".p2p/orm-db").getAbsolutePath();
        }
        return base;
    }

    static DsRegistry resolve(int srv, int pid) throws Exception {
        String k = srv + "_" + pid;
        DsRegistry t = TEST_INJECTS.get(k);
        if (t != null) return t;
        String base = defaultBaseRoot();
        String key = base + "|" + k;
        return INSTANCES.computeIfAbsent(key, unused -> {
            try {
                File root = new File(base, k);
                if (!root.exists() && !root.mkdirs()) {
                    throw new IllegalStateException("cannot create registry root " + root);
                }
                return new DsRegistry(root.getAbsolutePath(), srv, pid);
            } catch (Exception e) {
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException("DsRegistry(" + srv + "," + pid + ") 创建失败：" + e.getMessage(), e);
            }
        });
    }

    public static DsDatabaseLocal resolveOrm(int srv, int pid) throws Exception {
        String k = srv + "_" + pid;
        DsDatabaseLocal db = ORM_TEST_INJECTS.get(k);
        if (db != null) return db;
        String base = defaultOrmBaseRoot();
        String key = base + "|" + k;
        return ORM_INSTANCES.computeIfAbsent(key, unused -> {
            try {
                File root = new File(base, k);
                if (!root.exists() && !root.mkdirs()) {
                    throw new IllegalStateException("cannot create orm db root " + root);
                }
                return new DsDatabaseLocal(root, srv, pid);
            } catch (Exception e) {
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException("DsDatabaseLocal(" + srv + "," + pid + ") 创建失败：" + e.getMessage(), e);
            }
        });
    }

    public static void injectForTest(int srv, int pid, DsRegistry registry) {
        TEST_INJECTS.put(srv + "_" + pid, registry);
    }

    public static void injectOrmForTest(int srv, int pid, DsDatabaseLocal db) {
        ORM_TEST_INJECTS.put(srv + "_" + pid, db);
    }

    public static void clearForTest() {
        TEST_INJECTS.clear();
        INSTANCES.clear();
        ORM_TEST_INJECTS.clear();
        ORM_INSTANCES.clear();
    }
}
