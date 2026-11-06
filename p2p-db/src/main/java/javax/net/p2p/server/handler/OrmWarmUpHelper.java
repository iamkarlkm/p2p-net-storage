package javax.net.p2p.server.handler;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import java.io.File;

final class OrmWarmUpHelper {

    private OrmWarmUpHelper() {}

    @SuppressWarnings("unchecked")
    static <T extends DsTableAdapter> void warmUp(DsDatabaseLocal localDb, Class<T> clazz) throws Exception {
        java.lang.reflect.Constructor<?> noArg = clazz.getDeclaredConstructor();
        noArg.setAccessible(true);
        T dummy = (T) noArg.newInstance();
        if (dummy.getId() == null || dummy.getId() == 0L) {
            long id = localDb.putTable(dummy, false);
            if (id != 0L) {
                localDb.removeTable(clazz, id, false);
            }
        }
        File tmpDsHash = File.createTempFile("orm-warmup-dshash-", ".tmp");
        tmpDsHash.deleteOnExit();
        new com.q3lives.ds.collections.DsHashSet(tmpDsHash);
    }
}
