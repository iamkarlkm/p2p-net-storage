package javax.net.p2p.server.handler;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import java.io.File;
import java.nio.file.Files;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.DbNodeForceMovePartitionRequest;
import javax.net.p2p.model.DbNodeForceMovePartitionResponse;
import javax.net.p2p.model.P2PWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DbNodeForceMovePartitionServerHandlerTest {

    @Test
    public void shouldAcknowledgeValidMoveRequest() throws Exception {
        File home = Files.createTempDirectory("dsdb-force-move-valid").toFile();
        try (DsPartitionManager manager = new DsPartitionManager(home, 4, new String[0])) {
            DsNodeContext.register(1, manager);
            DbNodeForceMovePartitionRequest request = new DbNodeForceMovePartitionRequest(1, 2);
            P2PWrapper req = P2PWrapper.build(100, P2PCommand.DB_NODE_FORCE_MOVE_PARTITION, request);

            P2PWrapper resp = new DbNodeForceMovePartitionServerHandler().process(req);

            Assertions.assertEquals(P2PCommand.R_OK_DB_NODE_FORCE_MOVE_PARTITION, resp.getCommand());
            Assertions.assertTrue(resp.getData() instanceof DbNodeForceMovePartitionResponse);
            DbNodeForceMovePartitionResponse ok = (DbNodeForceMovePartitionResponse) resp.getData();
            Assertions.assertTrue(ok.ok);
            Assertions.assertTrue(ok.message.contains("acknowledged"));
            Assertions.assertTrue(ok.message.contains("serverId=1"));
            Assertions.assertTrue(ok.message.contains("partitionId=2"));
        } finally {
            DsNodeContext.clear();
        }
    }

    @Test
    public void shouldRejectMismatchedCommand() {
        DbNodeForceMovePartitionRequest request = new DbNodeForceMovePartitionRequest(1, 0);
        P2PWrapper req = P2PWrapper.build(101, P2PCommand.HEART_PING, request);

        P2PWrapper resp = new DbNodeForceMovePartitionServerHandler().process(req);

        Assertions.assertEquals(P2PCommand.STD_ERROR, resp.getCommand());
    }

    @Test
    public void shouldRejectInvalidPayload() {
        P2PWrapper req = P2PWrapper.build(102, P2PCommand.DB_NODE_FORCE_MOVE_PARTITION, "not-a-request");

        P2PWrapper resp = new DbNodeForceMovePartitionServerHandler().process(req);

        Assertions.assertEquals(P2PCommand.STD_ERROR, resp.getCommand());
    }

    @Test
    public void shouldRejectOutOfRangeServerId() {
        DbNodeForceMovePartitionRequest request = new DbNodeForceMovePartitionRequest((int) (DsIdLayout.SERVER_ID_MASK + 1), 0);
        P2PWrapper req = P2PWrapper.build(103, P2PCommand.DB_NODE_FORCE_MOVE_PARTITION, request);

        P2PWrapper resp = new DbNodeForceMovePartitionServerHandler().process(req);

        Assertions.assertEquals(P2PCommand.STD_ERROR, resp.getCommand());
    }

    @Test
    public void shouldRejectOutOfRangePartitionId() throws Exception {
        File home = Files.createTempDirectory("dsdb-force-move-partition").toFile();
        try (DsPartitionManager manager = new DsPartitionManager(home, 4, new String[0])) {
            DsNodeContext.register(1, manager);
            DbNodeForceMovePartitionRequest request = new DbNodeForceMovePartitionRequest(1, 4);
            P2PWrapper req = P2PWrapper.build(104, P2PCommand.DB_NODE_FORCE_MOVE_PARTITION, request);

            P2PWrapper resp = new DbNodeForceMovePartitionServerHandler().process(req);

            Assertions.assertEquals(P2PCommand.STD_ERROR, resp.getCommand());
        } finally {
            DsNodeContext.clear();
        }
    }

    @Test
    public void shouldRejectUnregisteredServer() {
        DbNodeForceMovePartitionRequest request = new DbNodeForceMovePartitionRequest(7, 0);
        P2PWrapper req = P2PWrapper.build(105, P2PCommand.DB_NODE_FORCE_MOVE_PARTITION, request);

        P2PWrapper resp = new DbNodeForceMovePartitionServerHandler().process(req);

        Assertions.assertEquals(P2PCommand.STD_ERROR, resp.getCommand());
    }
}
