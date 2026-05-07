package javax.net.p2p.server.handler;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.util.DsPathUtil;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbMetaGetRequest;
import javax.net.p2p.model.DbMetaGetResponse;
import javax.net.p2p.model.P2PWrapper;

public class DbMetaGetServerHandler implements P2PCommandHandler {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_META_GET;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_META_GET) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbMetaGetRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }

            DsDatabaseLocal db = DsDatabaseLocal.load();
            File dbRoot = db.getRoot();
            if (payload.ensureFresh) {
                Class<? extends DsTableAdapter> entityClass = tryLoadEntityClass(payload.entityClassName);
                if (entityClass != null) {
                    new TableMetaStore(dbRoot).ensureMeta(entityClass);
                }
            }

            byte[] tableBytes = payload.includeTableMeta ? readTableMeta(dbRoot, payload.entityClassName) : null;
            byte[] columnsBytes = payload.includeColumnsMeta ? readColumnsMeta(dbRoot, payload.entityClassName) : null;

            DbMetaGetResponse resp = new DbMetaGetResponse(
                payload.entityClassName,
                tableBytes,
                columnsBytes,
                sha256Hex(tableBytes),
                sha256Hex(columnsBytes)
            );
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_META_GET, resp);
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }

    private static byte[] readTableMeta(File dbRoot, String entityClassName) throws Exception {
        File metaFile = metaFile(dbRoot, entityClassName, "table.meta.yaml");
        if (!metaFile.isFile()) {
            return new byte[0];
        }
        return Files.readAllBytes(metaFile.toPath());
    }

    private static byte[] readColumnsMeta(File dbRoot, String entityClassName) throws Exception {
        File metaFile = metaFile(dbRoot, entityClassName, "columns.meta.yaml");
        if (!metaFile.isFile()) {
            return new byte[0];
        }
        return Files.readAllBytes(metaFile.toPath());
    }

    private static File metaFile(File dbRoot, String entityClassName, String fileName) {
        String spacePath = DsPathUtil.dottedToLinuxPath(entityClassName, "entityClass");
        File dir = new File(dbRoot, "indexes/" + spacePath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, fileName);
    }

    private static Class<? extends DsTableAdapter> tryLoadEntityClass(String entityClassName) {
        try {
            Class<?> raw = Class.forName(entityClassName);
            if (!DsTableAdapter.class.isAssignableFrom(raw)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Class<? extends DsTableAdapter> casted = (Class<? extends DsTableAdapter>) raw;
            return casted;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        if (data == null) {
            return "";
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }
}
