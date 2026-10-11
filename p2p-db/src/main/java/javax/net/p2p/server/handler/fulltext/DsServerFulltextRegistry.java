package javax.net.p2p.server.handler.fulltext;

import com.q3lives.ds.database.fulltext.FulltextStore;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public final class DsServerFulltextRegistry {
    private static final ConcurrentHashMap<String, FulltextStore.ShardRouter> ROUTERS = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();

    private DsServerFulltextRegistry() {
    }

    /** Parse cluster root string like "/path?sc=N&pfx=xxx" → returns array [0]=path, [1]=shardCountStr, [2]=shardPrefix */
    private static String[] parse(String encoded) {
        String path = encoded;
        String sc = null;
        String pfx = null;
        int q = encoded.indexOf('?');
        if (q >= 0) {
            path = encoded.substring(0, q);
            String query = encoded.substring(q + 1);
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String k = part.substring(0, eq);
                String v = part.substring(eq + 1);
                if (k.equals("sc")) sc = v;
                else if (k.equals("pfx")) pfx = v;
            }
        }
        return new String[]{ path, sc, pfx };
    }

    public static FulltextStore.ShardRouter getOrCreate(String encodedClusterRoot, int defaultShardCount, String defaultShardPrefix) throws IOException {
        String key = encodedClusterRoot;
        FulltextStore.ShardRouter r = ROUTERS.get(key);
        if (r != null) return r;
        synchronized (LOCK) {
            r = ROUTERS.get(key);
            if (r != null) return r;
            String[] parsed = parse(encodedClusterRoot);
            String path = parsed[0];
            int sc = (parsed[1] != null && !parsed[1].isEmpty()) ? Integer.parseInt(parsed[1]) : defaultShardCount;
            String pfx = (parsed[2] != null && !parsed[2].isEmpty()) ? parsed[2] : defaultShardPrefix;
            if (sc <= 0) sc = 1;
            File root = new File(path);
            root.mkdirs();
            r = new FulltextStore.ShardRouter(sc, root.getAbsolutePath() + "/" + pfx);
            ROUTERS.put(key, r);
            return r;
        }
    }

    public static void clearAll() {
        synchronized (LOCK) {
            for (FulltextStore.ShardRouter r : ROUTERS.values()) {
                try { r.close(); } catch (Exception ignore) { }
            }
            ROUTERS.clear();
        }
    }
}
