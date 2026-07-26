package com.q3lives.ds.database.startup;

import com.q3lives.ds.database.DsDatabaseServer;
import com.q3lives.ds.database.config.DsDatabaseClientConfig;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.AbstractP2PClient;
import javax.net.p2p.model.DbMetaGetRequest;
import javax.net.p2p.model.DbMetaGetResponse;
import javax.net.p2p.model.P2PWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DsDbClientMetaPrecheckTest {
    @Test
    public void strictShouldFailOnMetaChangedAndNotOverwriteLocalCache() throws Exception {
        File dbRoot = Files.createTempDirectory("dsdb-meta-precheck").toFile();
        String entity = "com.test.Entity";

        File dir = new File(dbRoot, "indexes/com/test/Entity");
        Assertions.assertTrue(dir.mkdirs());
        Files.writeString(new File(dir, "table.meta.yaml").toPath(), "signature: old\n", StandardCharsets.UTF_8);
        Files.writeString(new File(dir, "columns.meta.yaml").toPath(), "any: 1\n", StandardCharsets.UTF_8);

        FakeClient client = new FakeClient(entity, "signature: new\n", "any: 2\n");
        DsDatabaseServer server = new DsDatabaseServer(client, dbRoot);

        DsDatabaseClientConfig.MetaCheck cfg = new DsDatabaseClientConfig.MetaCheck();
        cfg.enabled = true;
        cfg.strict = true;
        cfg.ensureFresh = false;
        cfg.requireCache = true;
        cfg.entityClasses = List.of(entity);

        IllegalStateException ex = Assertions.assertThrows(
            IllegalStateException.class,
            () -> DsDbClientMetaPrecheck.runOrThrow(server, dbRoot, cfg)
        );
        Assertions.assertTrue(ex.getMessage().contains(entity));

        String table = Files.readString(new File(dir, "table.meta.yaml").toPath(), StandardCharsets.UTF_8);
        String columns = Files.readString(new File(dir, "columns.meta.yaml").toPath(), StandardCharsets.UTF_8);
        Assertions.assertTrue(table.contains("old"));
        Assertions.assertTrue(columns.contains("1"));
    }

    static final class FakeClient extends AbstractP2PClient {
        private final String entity;
        private final byte[] tableYaml;
        private final byte[] columnsYaml;

        FakeClient(String entity, String tableYaml, String columnsYaml) {
            super(new InetSocketAddress("127.0.0.1", 0), 16, 1, 0);
            this.entity = entity;
            this.tableYaml = tableYaml.getBytes(StandardCharsets.UTF_8);
            this.columnsYaml = columnsYaml.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public javax.net.p2p.common.AbstractSendMesageExecutor newSendMesageExecutorToQueue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper excute(P2PWrapper request, long timeout, java.util.concurrent.TimeUnit unit) {
            if (request.getCommand() != P2PCommand.DB_META_GET) {
                throw new AssertionError("unexpected command: " + request.getCommand());
            }
            Object payload = request.getData();
            if (!(payload instanceof DbMetaGetRequest r) || !entity.equals(r.entityClassName)) {
                throw new AssertionError("unexpected payload");
            }
            DbMetaGetResponse ok = new DbMetaGetResponse(entity, tableYaml, columnsYaml, null, null);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_META_GET, ok);
        }
    }
}

