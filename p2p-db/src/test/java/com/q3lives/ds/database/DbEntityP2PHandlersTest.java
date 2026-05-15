package com.q3lives.ds.database;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.database.remote.DbEntityRelationsCodec;
import com.q3lives.ds.database.integration.QueryWrapper;
import com.q3lives.ds.example.UserEntity;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.DbEntityExistsRequest;
import javax.net.p2p.model.DbEntityExistsResponse;
import javax.net.p2p.model.DbEntityGetRequest;
import javax.net.p2p.model.DbEntityGetResponse;
import javax.net.p2p.model.DbEntityPutRequest;
import javax.net.p2p.model.DbEntityPutResponse;
import javax.net.p2p.model.DbEntityQueryIdsRequest;
import javax.net.p2p.model.DbEntityQueryIdsResponse;
import javax.net.p2p.model.DbEntityRemoveRequest;
import javax.net.p2p.model.DbEntityRemoveResponse;
import javax.net.p2p.model.DbMetaGetRequest;
import javax.net.p2p.model.DbMetaGetResponse;
import javax.net.p2p.model.DbMetaPutRequest;
import javax.net.p2p.model.DbColumnSchema;
import javax.net.p2p.model.DbCompositeGroupSchema;
import javax.net.p2p.model.DbCompositeItemSchema;
import javax.net.p2p.model.DbTableSchema;
import javax.net.p2p.model.DbCellValue;
import javax.net.p2p.model.DbRowAllocRequest;
import javax.net.p2p.model.DbRowAllocResponse;
import javax.net.p2p.model.DbRowExistsRequest;
import javax.net.p2p.model.DbRowExistsResponse;
import javax.net.p2p.model.DbRowRemoveRequest;
import javax.net.p2p.model.DbRowRemoveResponse;
import javax.net.p2p.model.DbRowListIdsRequest;
import javax.net.p2p.model.DbRowListIdsResponse;
import javax.net.p2p.model.DbColPutRequest;
import javax.net.p2p.model.DbColPutResponse;
import javax.net.p2p.model.DbColGetRequest;
import javax.net.p2p.model.DbColGetResponse;
import javax.net.p2p.model.DbColRemoveRequest;
import javax.net.p2p.model.DbColRemoveResponse;
import javax.net.p2p.model.DbRowPutRequest;
import javax.net.p2p.model.DbRowPutResponse;
import javax.net.p2p.model.DbRowGetRequest;
import javax.net.p2p.model.DbRowGetResponse;
import javax.net.p2p.model.DbRowCountRequest;
import javax.net.p2p.model.DbRowCountResponse;
import javax.net.p2p.model.DbRowExistsByQueryRequest;
import javax.net.p2p.model.DbRowExistsByQueryResponse;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;
import javax.net.p2p.model.DbQueryOrGroup;
import javax.net.p2p.model.DbQueryOrder;
import javax.net.p2p.model.DbRowQueryIdsRequest;
import javax.net.p2p.model.DbRowQueryIdsResponse;
import javax.net.p2p.model.DbIndexCreateRequest;
import javax.net.p2p.model.DbIndexCreateResponse;
import javax.net.p2p.model.DbIndexDropRequest;
import javax.net.p2p.model.DbIndexDropResponse;
import javax.net.p2p.model.DbIndexListRequest;
import javax.net.p2p.model.DbIndexListResponse;
import javax.net.p2p.model.DbIndexInfoRequest;
import javax.net.p2p.model.DbIndexInfoResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.handler.DbEntityExistsServerHandler;
import javax.net.p2p.server.handler.DbEntityGetServerHandler;
import javax.net.p2p.server.handler.DbEntityPutServerHandler;
import javax.net.p2p.server.handler.DbEntityQueryIdsServerHandler;
import javax.net.p2p.server.handler.DbEntityRemoveServerHandler;
import javax.net.p2p.server.handler.DbMetaGetServerHandler;
import javax.net.p2p.server.handler.DbMetaPutServerHandler;
import javax.net.p2p.server.handler.DbRowAllocServerHandler;
import javax.net.p2p.server.handler.DbRowExistsServerHandler;
import javax.net.p2p.server.handler.DbRowRemoveServerHandler;
import javax.net.p2p.server.handler.DbRowListIdsServerHandler;
import javax.net.p2p.server.handler.DbColPutServerHandler;
import javax.net.p2p.server.handler.DbColGetServerHandler;
import javax.net.p2p.server.handler.DbColRemoveServerHandler;
import javax.net.p2p.server.handler.DbRowPutServerHandler;
import javax.net.p2p.server.handler.DbRowGetServerHandler;
import javax.net.p2p.server.handler.DbRowCountServerHandler;
import javax.net.p2p.server.handler.DbRowExistsByQueryServerHandler;
import javax.net.p2p.server.handler.DbRowQueryIdsServerHandler;
import javax.net.p2p.server.handler.DbIndexCreateServerHandler;
import javax.net.p2p.server.handler.DbIndexDropServerHandler;
import javax.net.p2p.server.handler.DbIndexListServerHandler;
import javax.net.p2p.server.handler.DbIndexInfoServerHandler;
import javax.net.p2p.utils.SerializationUtil;

public class DbEntityP2PHandlersTest {
    
    @Test
    public void testPutGetViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            
            UserEntity user = new UserEntity();
            user.setUsername("bob");
            user.setAge(30);
            user.setActive(true);
            user.setUserLevel(3);
            
            ByteBuffer buf = user.toBytes();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            
            DbEntityPutRequest putPayload = new DbEntityPutRequest(UserEntity.class.getName(), bytes, false);
            P2PWrapper putReq = P2PWrapper.build(1, P2PCommand.DB_ENTITY_PUT, putPayload);
            
            P2PWrapper putResp = new DbEntityPutServerHandler().process(putReq);
            Assertions.assertEquals(P2PCommand.R_OK_DB_ENTITY_PUT, putResp.getCommand());
            Assertions.assertTrue(putResp.getData() instanceof DbEntityPutResponse);
            long id = ((DbEntityPutResponse) putResp.getData()).id;
            Assertions.assertTrue(id != 0L);
            
            DbEntityGetRequest getPayload = new DbEntityGetRequest(UserEntity.class.getName(), id, false);
            P2PWrapper getReq = P2PWrapper.build(2, P2PCommand.DB_ENTITY_GET, getPayload);
            
            P2PWrapper getResp = new DbEntityGetServerHandler().process(getReq);
            Assertions.assertEquals(P2PCommand.R_OK_DB_ENTITY_GET, getResp.getCommand());
            Assertions.assertTrue(getResp.getData() instanceof DbEntityGetResponse);
            
            DbEntityGetResponse ok = (DbEntityGetResponse) getResp.getData();
            UserEntity loaded = new UserEntity();
            loaded.load(ByteBuffer.wrap(ok.bytes));
            
            Assertions.assertEquals("bob", loaded.getUsername());
            Assertions.assertEquals(30, loaded.getAge());
            Assertions.assertTrue(loaded.isActive());
            Assertions.assertEquals(3, loaded.getUserLevel());
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }
    
    @Test
    public void testPutPersistsRelationsWithRelationsPayload() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-rel").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            
            Profile profile = new Profile();
            profile.setNick("n1");
            
            UserWithProfile user = new UserWithProfile();
            user.setName("u1");
            user.setProfile(profile);
            
            ByteBuffer buf = user.toBytes();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            byte[] relations = DbEntityRelationsCodec.encode(user);
            
            DbEntityPutRequest putPayload = new DbEntityPutRequest(UserWithProfile.class.getName(), bytes, true, relations);
            P2PWrapper putReq = P2PWrapper.build(10, P2PCommand.DB_ENTITY_PUT, putPayload);
            P2PWrapper putResp = new DbEntityPutServerHandler().process(putReq);
            long id = ((DbEntityPutResponse) putResp.getData()).id;
            
            DbEntityGetRequest getPayload = new DbEntityGetRequest(UserWithProfile.class.getName(), id, true);
            P2PWrapper getReq = P2PWrapper.build(11, P2PCommand.DB_ENTITY_GET, getPayload);
            P2PWrapper getResp = new DbEntityGetServerHandler().process(getReq);
            DbEntityGetResponse ok = (DbEntityGetResponse) getResp.getData();
            
            UserWithProfile loaded = new UserWithProfile();
            loaded.load(ByteBuffer.wrap(ok.bytes));
            DbEntityRelationsCodec.apply(loaded, ok.relations);
            
            DsDatabaseLocal db = DsDatabaseLocal.load();
            UserWithProfile full = db.getTableWithRelations(UserWithProfile.class, loaded.getId());
            
            Assertions.assertEquals("u1", full.getName());
            Assertions.assertNotNull(full.getProfile());
            Assertions.assertEquals("n1", full.getProfile().getNick());
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testExistsRemoveAndQueryIdsViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-q").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            UserEntity user = new UserEntity();
            user.setUsername("bob");
            user.setAge(30);
            user.setActive(true);
            user.setUserLevel(3);

            ByteBuffer buf = user.toBytes();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);

            DbEntityPutRequest putPayload = new DbEntityPutRequest(UserEntity.class.getName(), bytes, false);
            P2PWrapper putReq = P2PWrapper.build(21, P2PCommand.DB_ENTITY_PUT, putPayload);
            P2PWrapper putResp = new DbEntityPutServerHandler().process(putReq);
            long id = ((DbEntityPutResponse) putResp.getData()).id;

            DbEntityExistsRequest existsPayload = new DbEntityExistsRequest(UserEntity.class.getName(), id);
            P2PWrapper existsReq = P2PWrapper.build(22, P2PCommand.DB_ENTITY_EXISTS, existsPayload);
            P2PWrapper existsResp = new DbEntityExistsServerHandler().process(existsReq);
            Assertions.assertEquals(P2PCommand.R_OK_DB_ENTITY_EXISTS, existsResp.getCommand());
            Assertions.assertTrue(((DbEntityExistsResponse) existsResp.getData()).exists);

            QueryWrapper<UserEntity> wrapper = new QueryWrapper<>();
            wrapper.eq("username", "bob");
            byte[] wrapperBytes = SerializationUtil.serialize(wrapper);
            DbEntityQueryIdsRequest queryPayload = new DbEntityQueryIdsRequest(UserEntity.class.getName(), wrapperBytes, 0, 0);
            P2PWrapper queryReq = P2PWrapper.build(23, P2PCommand.DB_ENTITY_QUERY_IDS, queryPayload);
            P2PWrapper queryResp = new DbEntityQueryIdsServerHandler().process(queryReq);
            Assertions.assertEquals(P2PCommand.R_OK_DB_ENTITY_QUERY_IDS, queryResp.getCommand());
            DbEntityQueryIdsResponse queryOk = (DbEntityQueryIdsResponse) queryResp.getData();
            long[] ids = SerializationUtil.deserialize(long[].class, queryOk.idsBytes);
            Assertions.assertTrue(Arrays.stream(ids).anyMatch(v -> v == id));

            DbEntityRemoveRequest rmPayload = new DbEntityRemoveRequest(UserEntity.class.getName(), id, true);
            P2PWrapper rmReq = P2PWrapper.build(24, P2PCommand.DB_ENTITY_REMOVE, rmPayload);
            P2PWrapper rmResp = new DbEntityRemoveServerHandler().process(rmReq);
            Assertions.assertEquals(P2PCommand.R_OK_DB_ENTITY_REMOVE, rmResp.getCommand());
            Assertions.assertTrue(((DbEntityRemoveResponse) rmResp.getData()).removed);

            P2PWrapper exists2Resp = new DbEntityExistsServerHandler().process(existsReq);
            Assertions.assertFalse(((DbEntityExistsResponse) exists2Resp.getData()).exists);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testGetMetaViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-meta").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            DbMetaGetRequest payload = new DbMetaGetRequest(UserEntity.class.getName(), true, true, true);
            P2PWrapper req = P2PWrapper.build(31, P2PCommand.DB_META_GET, payload);
            P2PWrapper resp = new DbMetaGetServerHandler().process(req);
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_GET, resp.getCommand());
            Assertions.assertTrue(resp.getData() instanceof DbMetaGetResponse);

            DbMetaGetResponse ok = (DbMetaGetResponse) resp.getData();
            Assertions.assertNotNull(ok.tableMetaYaml);
            Assertions.assertTrue(ok.tableMetaYaml.length > 0);
            Assertions.assertNotNull(ok.columnsMetaYaml);
            Assertions.assertTrue(ok.columnsMetaYaml.length > 0);

            Assertions.assertEquals(sha256Hex(ok.tableMetaYaml), ok.tableMetaSha256);
            Assertions.assertEquals(sha256Hex(ok.columnsMetaYaml), ok.columnsMetaSha256);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }
    
    @Test
    public void testGetMetaWithoutEntityClassOnServer() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-meta-unknown").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            
            String unknown = "com.example.NotOnServer";
            DbMetaGetRequest payload = new DbMetaGetRequest(unknown, true, true, true);
            P2PWrapper req = P2PWrapper.build(32, P2PCommand.DB_META_GET, payload);
            P2PWrapper resp = new DbMetaGetServerHandler().process(req);
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_GET, resp.getCommand());
            Assertions.assertTrue(resp.getData() instanceof DbMetaGetResponse);
            
            DbMetaGetResponse ok = (DbMetaGetResponse) resp.getData();
            Assertions.assertEquals(unknown, ok.entityClassName);
            Assertions.assertNotNull(ok.tableMetaYaml);
            Assertions.assertNotNull(ok.columnsMetaYaml);
            Assertions.assertEquals(0, ok.tableMetaYaml.length);
            Assertions.assertEquals(0, ok.columnsMetaYaml.length);
            Assertions.assertEquals(sha256Hex(ok.tableMetaYaml), ok.tableMetaSha256);
            Assertions.assertEquals(sha256Hex(ok.columnsMetaYaml), ok.columnsMetaSha256);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }
    
    @Test
    public void testPutMetaCreatesTableWithoutEntityClassOnServer() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-meta-put").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String entityClassName = "com.example.DynamicUser";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbCompositeGroupSchema group = new DbCompositeGroupSchema();
            group.group = "flags";
            group.length = 2;
            group.items.add(new DbCompositeItemSchema("active", 0, 0));
            group.items.add(new DbCompositeItemSchema("level", 1, 3));
            schema.compositeGroups.put(group.group, group);

            DbMetaPutRequest putPayload = new DbMetaPutRequest(entityClassName, schema, true);
            P2PWrapper putReq = P2PWrapper.build(33, P2PCommand.DB_META_PUT, putPayload);
            P2PWrapper putResp = new DbMetaPutServerHandler().process(putReq);
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, putResp.getCommand());
            Assertions.assertTrue(putResp.getData() instanceof DbMetaGetResponse);

            DbMetaGetResponse ok = (DbMetaGetResponse) putResp.getData();
            Assertions.assertEquals(entityClassName, ok.entityClassName);
            Assertions.assertNotNull(ok.tableMetaYaml);
            Assertions.assertNotNull(ok.columnsMetaYaml);
            Assertions.assertTrue(ok.tableMetaYaml.length > 0);
            Assertions.assertTrue(ok.columnsMetaYaml.length > 0);
            Assertions.assertEquals(sha256Hex(ok.tableMetaYaml), ok.tableMetaSha256);
            Assertions.assertEquals(sha256Hex(ok.columnsMetaYaml), ok.columnsMetaSha256);

            String tableYaml = new String(ok.tableMetaYaml, java.nio.charset.StandardCharsets.UTF_8);
            Assertions.assertTrue(tableYaml.contains("username"));
            Assertions.assertTrue(tableYaml.contains("flags"));
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testDynamicCrudViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-dyncrud").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbCompositeGroupSchema group = new DbCompositeGroupSchema();
            group.group = "flags";
            group.length = 2;
            group.items.add(new DbCompositeItemSchema("active", 0, 0));
            group.items.add(new DbCompositeItemSchema("level", 1, 3));
            schema.compositeGroups.put(group.group, group);

            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(40, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            DbRowAllocRequest alloc = new DbRowAllocRequest(table);
            P2PWrapper allocResp = new DbRowAllocServerHandler().process(P2PWrapper.build(41, P2PCommand.DB_ROW_ALLOC, alloc));
            Assertions.assertEquals(P2PCommand.R_OK_DB_ROW_ALLOC, allocResp.getCommand());
            long rowId = ((DbRowAllocResponse) allocResp.getData()).rowId;
            Assertions.assertTrue(rowId > 0);

            DbColPutRequest putName = new DbColPutRequest(table, rowId, "username", "alice".getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
            P2PWrapper putNameResp = new DbColPutServerHandler().process(P2PWrapper.build(42, P2PCommand.DB_COL_PUT, putName));
            Assertions.assertEquals(P2PCommand.R_OK_DB_COL_PUT, putNameResp.getCommand());
            Assertions.assertTrue(((DbColPutResponse) putNameResp.getData()).valueId > 0);

            DbColPutRequest putFlags = new DbColPutRequest(table, rowId, "@composite:flags", new byte[] {0b0000_0011, 0}, false);
            P2PWrapper putFlagsResp = new DbColPutServerHandler().process(P2PWrapper.build(43, P2PCommand.DB_COL_PUT, putFlags));
            Assertions.assertEquals(P2PCommand.R_OK_DB_COL_PUT, putFlagsResp.getCommand());

            DbColGetRequest getName = new DbColGetRequest(table, rowId, "username");
            P2PWrapper getNameResp = new DbColGetServerHandler().process(P2PWrapper.build(44, P2PCommand.DB_COL_GET, getName));
            Assertions.assertEquals(P2PCommand.R_OK_DB_COL_GET, getNameResp.getCommand());
            DbColGetResponse nameOk = (DbColGetResponse) getNameResp.getData();
            Assertions.assertTrue(nameOk.found);
            Assertions.assertEquals("alice", new String(nameOk.valueBytes, 0, 5, java.nio.charset.StandardCharsets.UTF_8));

            DbRowGetRequest getRowAll = new DbRowGetRequest(table, rowId, java.util.Collections.emptyList());
            P2PWrapper getRowResp = new DbRowGetServerHandler().process(P2PWrapper.build(45, P2PCommand.DB_ROW_GET, getRowAll));
            Assertions.assertEquals(P2PCommand.R_OK_DB_ROW_GET, getRowResp.getCommand());
            DbRowGetResponse rowOk = (DbRowGetResponse) getRowResp.getData();
            Assertions.assertEquals(rowId, rowOk.rowId);
            Assertions.assertTrue(rowOk.values.stream().anyMatch(v -> "username".equals(v.name)));
            Assertions.assertTrue(rowOk.values.stream().anyMatch(v -> "@composite:flags".equals(v.name)));

            DbRowListIdsRequest listIds = new DbRowListIdsRequest(table, 0, 100);
            P2PWrapper listResp = new DbRowListIdsServerHandler().process(P2PWrapper.build(46, P2PCommand.DB_ROW_LIST_IDS, listIds));
            Assertions.assertEquals(P2PCommand.R_OK_DB_ROW_LIST_IDS, listResp.getCommand());
            long[] ids = SerializationUtil.deserialize(long[].class, ((DbRowListIdsResponse) listResp.getData()).idsBytes);
            Assertions.assertTrue(Arrays.stream(ids).anyMatch(v -> v == rowId));

            DbRowExistsRequest exists = new DbRowExistsRequest(table, rowId);
            P2PWrapper existsResp = new DbRowExistsServerHandler().process(P2PWrapper.build(47, P2PCommand.DB_ROW_EXISTS, exists));
            Assertions.assertEquals(P2PCommand.R_OK_DB_ROW_EXISTS, existsResp.getCommand());
            Assertions.assertTrue(((DbRowExistsResponse) existsResp.getData()).exists);

            DbColRemoveRequest rmCol = new DbColRemoveRequest(table, rowId, "username");
            P2PWrapper rmColResp = new DbColRemoveServerHandler().process(P2PWrapper.build(48, P2PCommand.DB_COL_REMOVE, rmCol));
            Assertions.assertEquals(P2PCommand.R_OK_DB_COL_REMOVE, rmColResp.getCommand());
            Assertions.assertTrue(((DbColRemoveResponse) rmColResp.getData()).removed);

            DbRowRemoveRequest rmRow = new DbRowRemoveRequest(table, rowId);
            P2PWrapper rmRowResp = new DbRowRemoveServerHandler().process(P2PWrapper.build(49, P2PCommand.DB_ROW_REMOVE, rmRow));
            Assertions.assertEquals(P2PCommand.R_OK_DB_ROW_REMOVE, rmRowResp.getCommand());
            Assertions.assertTrue(((DbRowRemoveResponse) rmRowResp.getData()).removed);

            P2PWrapper exists2Resp = new DbRowExistsServerHandler().process(P2PWrapper.build(50, P2PCommand.DB_ROW_EXISTS, exists));
            Assertions.assertFalse(((DbRowExistsResponse) exists2Resp.getData()).exists);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testDynamicRowPutAllocatesWhenRowIdZero() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-dyncrud-putrow").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserV2";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(60, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            java.util.List<DbCellValue> values = new java.util.ArrayList<>();
            values.add(new DbCellValue("username", "bob".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            DbRowPutRequest putRow = new DbRowPutRequest(table, 0L, true, values);
            P2PWrapper putRowResp = new DbRowPutServerHandler().process(P2PWrapper.build(61, P2PCommand.DB_ROW_PUT, putRow));
            Assertions.assertEquals(P2PCommand.R_OK_DB_ROW_PUT, putRowResp.getCommand());
            long rowId = ((DbRowPutResponse) putRowResp.getData()).rowId;
            Assertions.assertTrue(rowId > 0L);

            DbColGetRequest getName = new DbColGetRequest(table, rowId, "username");
            P2PWrapper getNameResp = new DbColGetServerHandler().process(P2PWrapper.build(62, P2PCommand.DB_COL_GET, getName));
            DbColGetResponse ok = (DbColGetResponse) getNameResp.getData();
            Assertions.assertTrue(ok.found);
            Assertions.assertEquals("bob", new String(ok.valueBytes, 0, 3, java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testDynamicQueryIdsViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-dynquery").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserQueryV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(70, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            long r1 = putRow(table, "amy", 18);
            long r2 = putRow(table, "alice", 30);
            long r3 = putRow(table, "bob", 40);
            Assertions.assertTrue(r1 > 0 && r2 > 0 && r3 > 0);

            DbQuery q = new DbQuery();
            q.where.add(new DbQueryCriterion(DbQueryOp.GT, "age", "20", null, null));
            q.orderBy.add(new DbQueryOrder("age", false));

            DbRowQueryIdsRequest queryReq = new DbRowQueryIdsRequest(table, q, 0, 10);
            P2PWrapper resp = new DbRowQueryIdsServerHandler().process(P2PWrapper.build(71, P2PCommand.DB_ROW_QUERY_IDS, queryReq));
            Assertions.assertEquals(P2PCommand.R_OK_DB_ROW_QUERY_IDS, resp.getCommand());
            DbRowQueryIdsResponse ok = (DbRowQueryIdsResponse) resp.getData();
            long[] ids = SerializationUtil.deserialize(long[].class, ok.idsBytes);
            Assertions.assertNotNull(ids);
            Assertions.assertEquals(2, ids.length);
            Assertions.assertEquals(r3, ids[0]);
            Assertions.assertEquals(r2, ids[1]);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testDynamicEqIndexCreateAndMaintenanceViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-dynidx").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserIndexV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(80, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            long r1 = putRow(table, "bob", 10);
            long r2 = putRow(table, "alice", 11);
            long r3 = putRow(table, "bob", 12);
            Assertions.assertTrue(r1 > 0 && r2 > 0 && r3 > 0);

            DbIndexCreateRequest createIdx = new DbIndexCreateRequest(table, "username");
            P2PWrapper idxResp = new DbIndexCreateServerHandler().process(P2PWrapper.build(81, P2PCommand.DB_INDEX_CREATE, createIdx));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_CREATE, idxResp.getCommand());
            Assertions.assertTrue(((DbIndexCreateResponse) idxResp.getData()).created);

            long[] idsBob = queryEq(table, "username", "bob");
            Assertions.assertEquals(2, idsBob.length);
            Assertions.assertTrue(Arrays.stream(idsBob).anyMatch(v -> v == r1));
            Assertions.assertTrue(Arrays.stream(idsBob).anyMatch(v -> v == r3));

            DbColPutRequest rename = new DbColPutRequest(table, r1, "username", "carl".getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
            P2PWrapper renameResp = new DbColPutServerHandler().process(P2PWrapper.build(82, P2PCommand.DB_COL_PUT, rename));
            Assertions.assertEquals(P2PCommand.R_OK_DB_COL_PUT, renameResp.getCommand());

            long[] idsBob2 = queryEq(table, "username", "bob");
            Assertions.assertEquals(1, idsBob2.length);
            Assertions.assertEquals(r3, idsBob2[0]);

            long[] idsCarl = queryEq(table, "username", "carl");
            Assertions.assertEquals(1, idsCarl.length);
            Assertions.assertEquals(r1, idsCarl[0]);

            DbColRemoveRequest rm = new DbColRemoveRequest(table, r3, "username");
            P2PWrapper rmResp = new DbColRemoveServerHandler().process(P2PWrapper.build(83, P2PCommand.DB_COL_REMOVE, rm));
            Assertions.assertEquals(P2PCommand.R_OK_DB_COL_REMOVE, rmResp.getCommand());
            Assertions.assertTrue(((DbColRemoveResponse) rmResp.getData()).removed);

            long[] idsBob3 = queryEq(table, "username", "bob");
            Assertions.assertEquals(0, idsBob3.length);

            DbRowRemoveRequest rmRow = new DbRowRemoveRequest(table, r1);
            P2PWrapper rmRowResp = new DbRowRemoveServerHandler().process(P2PWrapper.build(84, P2PCommand.DB_ROW_REMOVE, rmRow));
            Assertions.assertEquals(P2PCommand.R_OK_DB_ROW_REMOVE, rmRowResp.getCommand());
            Assertions.assertTrue(((DbRowRemoveResponse) rmRowResp.getData()).removed);

            long[] idsCarl2 = queryEq(table, "username", "carl");
            Assertions.assertEquals(0, idsCarl2.length);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testDynamicEqIndexMultiEqQueryViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-dynidx-multi-eq").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserIndexMultiEqV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(85, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            long r1 = putRow(table, "bob", 10);
            long r2 = putRow(table, "bob", 12);
            long r3 = putRow(table, "alice", 12);
            Assertions.assertTrue(r1 > 0 && r2 > 0 && r3 > 0);

            P2PWrapper idxUser = new DbIndexCreateServerHandler().process(P2PWrapper.build(86, P2PCommand.DB_INDEX_CREATE, new DbIndexCreateRequest(table, "username")));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_CREATE, idxUser.getCommand());
            Assertions.assertTrue(((DbIndexCreateResponse) idxUser.getData()).created);

            P2PWrapper idxAge = new DbIndexCreateServerHandler().process(P2PWrapper.build(87, P2PCommand.DB_INDEX_CREATE, new DbIndexCreateRequest(table, "age")));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_CREATE, idxAge.getCommand());
            Assertions.assertTrue(((DbIndexCreateResponse) idxAge.getData()).created);

            DbQuery q = new DbQuery();
            q.where.add(new DbQueryCriterion(DbQueryOp.EQ, "username", "bob", null, null));
            q.where.add(new DbQueryCriterion(DbQueryOp.EQ, "age", "12", null, null));
            long[] ids = queryIds(table, q);
            Assertions.assertEquals(1, ids.length);
            Assertions.assertEquals(r2, ids[0]);

            DbQuery q2 = new DbQuery();
            q2.where.add(new DbQueryCriterion(DbQueryOp.EQ, "username", "bob", null, null));
            q2.where.add(new DbQueryCriterion(DbQueryOp.EQ, "age", "11", null, null));
            long[] ids2 = queryIds(table, q2);
            Assertions.assertEquals(0, ids2.length);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testDynamicEqIndexInQueryViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-dynidx-in").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserIndexInV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(88, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            long r1 = putRow(table, "bob", 10);
            long r2 = putRow(table, "bob", 12);
            long r3 = putRow(table, "alice", 12);
            long r4 = putRow(table, "amy", 12);
            Assertions.assertTrue(r1 > 0 && r2 > 0 && r3 > 0 && r4 > 0);

            P2PWrapper idxUser = new DbIndexCreateServerHandler().process(P2PWrapper.build(89, P2PCommand.DB_INDEX_CREATE, new DbIndexCreateRequest(table, "username")));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_CREATE, idxUser.getCommand());
            Assertions.assertTrue(((DbIndexCreateResponse) idxUser.getData()).created);

            DbQuery q = new DbQuery();
            q.where.add(new DbQueryCriterion(DbQueryOp.IN, "username", null, null, java.util.List.of("bob", "alice")));
            q.where.add(new DbQueryCriterion(DbQueryOp.EQ, "age", "12", null, null));
            long[] ids = queryIds(table, q);
            Assertions.assertEquals(2, ids.length);
            Assertions.assertTrue(Arrays.stream(ids).anyMatch(v -> v == r2));
            Assertions.assertTrue(Arrays.stream(ids).anyMatch(v -> v == r3));

            DbQuery q2 = new DbQuery();
            q2.where.add(new DbQueryCriterion(DbQueryOp.IN, "username", null, null, java.util.List.of("bob", "alice")));
            q2.where.add(new DbQueryCriterion(DbQueryOp.EQ, "age", "10", null, null));
            long[] ids2 = queryIds(table, q2);
            Assertions.assertEquals(1, ids2.length);
            Assertions.assertEquals(r1, ids2[0]);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testDynamicOrGroupsQueryViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-dyn-or-groups").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserOrGroupsV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(120, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            long r1 = putRow(table, "bob", 12);
            long r2 = putRow(table, "alice", 10);
            long r3 = putRow(table, "amy", 12);
            Assertions.assertTrue(r1 > 0 && r2 > 0 && r3 > 0);

            P2PWrapper idxUser = new DbIndexCreateServerHandler().process(P2PWrapper.build(121, P2PCommand.DB_INDEX_CREATE, new DbIndexCreateRequest(table, "username")));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_CREATE, idxUser.getCommand());
            Assertions.assertTrue(((DbIndexCreateResponse) idxUser.getData()).created);

            DbQuery q = new DbQuery();
            DbQueryOrGroup g1 = new DbQueryOrGroup();
            g1.where.add(new DbQueryCriterion(DbQueryOp.EQ, "username", "bob", null, null));
            DbQueryOrGroup g2 = new DbQueryOrGroup();
            g2.where.add(new DbQueryCriterion(DbQueryOp.EQ, "username", "alice", null, null));
            q.anyOf.add(g1);
            q.anyOf.add(g2);
            q.where.add(new DbQueryCriterion(DbQueryOp.EQ, "age", "12", null, null));

            long[] ids = queryIds(table, q);
            Assertions.assertEquals(1, ids.length);
            Assertions.assertEquals(r1, ids[0]);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testDynamicNotInQueryViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-dyn-not-in").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserNotInV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(130, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            long r1 = putRow(table, "bob", 12);
            long r2 = putRow(table, "alice", 12);
            long r3 = putRow(table, "amy", 12);
            Assertions.assertTrue(r1 > 0 && r2 > 0 && r3 > 0);

            P2PWrapper idxUser = new DbIndexCreateServerHandler().process(P2PWrapper.build(131, P2PCommand.DB_INDEX_CREATE, new DbIndexCreateRequest(table, "username")));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_CREATE, idxUser.getCommand());
            Assertions.assertTrue(((DbIndexCreateResponse) idxUser.getData()).created);

            DbQuery q = new DbQuery();
            q.where.add(new DbQueryCriterion(DbQueryOp.NOT_IN, "username", null, null, java.util.List.of("bob", "amy")));
            q.where.add(new DbQueryCriterion(DbQueryOp.EQ, "age", "12", null, null));
            long[] ids = queryIds(table, q);
            Assertions.assertEquals(1, ids.length);
            Assertions.assertEquals(r2, ids[0]);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testDynamicCountQueryViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-dyn-count").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserCountV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(140, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            long r1 = putRow(table, "bob", 12);
            long r2 = putRow(table, "alice", 12);
            long r3 = putRow(table, "bob", 10);
            Assertions.assertTrue(r1 > 0 && r2 > 0 && r3 > 0);

            P2PWrapper idxUser = new DbIndexCreateServerHandler().process(P2PWrapper.build(141, P2PCommand.DB_INDEX_CREATE, new DbIndexCreateRequest(table, "username")));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_CREATE, idxUser.getCommand());
            Assertions.assertTrue(((DbIndexCreateResponse) idxUser.getData()).created);

            DbQuery q = new DbQuery();
            DbQueryOrGroup g1 = new DbQueryOrGroup();
            g1.where.add(new DbQueryCriterion(DbQueryOp.EQ, "username", "bob", null, null));
            DbQueryOrGroup g2 = new DbQueryOrGroup();
            g2.where.add(new DbQueryCriterion(DbQueryOp.EQ, "username", "alice", null, null));
            q.anyOf.add(g1);
            q.anyOf.add(g2);
            q.where.add(new DbQueryCriterion(DbQueryOp.EQ, "age", "12", null, null));

            long count = countRows(table, q);
            Assertions.assertEquals(2L, count);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testDynamicExistsByQueryViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-dyn-exists-by-query").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserExistsByQueryV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(150, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            long r1 = putRow(table, "bob", 12);
            long r2 = putRow(table, "alice", 10);
            Assertions.assertTrue(r1 > 0 && r2 > 0);

            P2PWrapper idxUser = new DbIndexCreateServerHandler().process(P2PWrapper.build(151, P2PCommand.DB_INDEX_CREATE, new DbIndexCreateRequest(table, "username")));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_CREATE, idxUser.getCommand());
            Assertions.assertTrue(((DbIndexCreateResponse) idxUser.getData()).created);

            DbQuery yes = new DbQuery();
            yes.where.add(new DbQueryCriterion(DbQueryOp.EQ, "username", "bob", null, null));
            Assertions.assertTrue(existsByQuery(table, yes));

            DbQuery no = new DbQuery();
            no.where.add(new DbQueryCriterion(DbQueryOp.EQ, "username", "nobody", null, null));
            Assertions.assertFalse(existsByQuery(table, no));
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testDynamicEqIndexDropViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-dynidx-drop").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserIndexDropV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(90, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            long r1 = putRow(table, "bob", 10);
            long r2 = putRow(table, "alice", 11);
            long r3 = putRow(table, "bob", 12);
            Assertions.assertTrue(r1 > 0 && r2 > 0 && r3 > 0);

            DbIndexCreateRequest createIdx = new DbIndexCreateRequest(table, "username");
            P2PWrapper idxResp = new DbIndexCreateServerHandler().process(P2PWrapper.build(91, P2PCommand.DB_INDEX_CREATE, createIdx));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_CREATE, idxResp.getCommand());
            Assertions.assertTrue(((DbIndexCreateResponse) idxResp.getData()).created);

            DbIndexDropRequest dropIdx = new DbIndexDropRequest(table, "username");
            P2PWrapper dropResp = new DbIndexDropServerHandler().process(P2PWrapper.build(92, P2PCommand.DB_INDEX_DROP, dropIdx));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_DROP, dropResp.getCommand());
            Assertions.assertTrue(((DbIndexDropResponse) dropResp.getData()).dropped);

            long[] idsBob = queryEq(table, "username", "bob");
            Assertions.assertEquals(2, idsBob.length);
            Assertions.assertTrue(Arrays.stream(idsBob).anyMatch(v -> v == r1));
            Assertions.assertTrue(Arrays.stream(idsBob).anyMatch(v -> v == r3));
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testDynamicEqIndexListAndInfoViaHandlers() throws Exception {
        File home = Files.createTempDirectory("dsdb-p2p-handlers-dynidx-listinfo").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());

            String table = "com.example.DynamicUserIndexListInfoV1";
            DbTableSchema schema = new DbTableSchema();
            schema.columns.add(new DbColumnSchema("username", "java.lang.String", 32, 0, 0));
            schema.columns.add(new DbColumnSchema("age", "int", 4, 0, 0));
            DbMetaPutRequest putMeta = new DbMetaPutRequest(table, schema, true);
            P2PWrapper metaResp = new DbMetaPutServerHandler().process(P2PWrapper.build(100, P2PCommand.DB_META_PUT, putMeta));
            Assertions.assertEquals(P2PCommand.R_OK_DB_META_PUT, metaResp.getCommand());

            long r1 = putRow(table, "bob", 10);
            long r2 = putRow(table, "alice", 11);
            Assertions.assertTrue(r1 > 0 && r2 > 0);

            P2PWrapper list0 = new DbIndexListServerHandler().process(P2PWrapper.build(101, P2PCommand.DB_INDEX_LIST, new DbIndexListRequest(table)));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_LIST, list0.getCommand());
            DbIndexListResponse l0 = (DbIndexListResponse) list0.getData();
            Assertions.assertTrue(l0.indexes == null || l0.indexes.isEmpty());

            P2PWrapper info0 = new DbIndexInfoServerHandler().process(P2PWrapper.build(102, P2PCommand.DB_INDEX_INFO, new DbIndexInfoRequest(table, "username")));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_INFO, info0.getCommand());
            DbIndexInfoResponse i0 = (DbIndexInfoResponse) info0.getData();
            Assertions.assertFalse(i0.exists);

            P2PWrapper idxResp = new DbIndexCreateServerHandler().process(P2PWrapper.build(103, P2PCommand.DB_INDEX_CREATE, new DbIndexCreateRequest(table, "username")));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_CREATE, idxResp.getCommand());
            Assertions.assertTrue(((DbIndexCreateResponse) idxResp.getData()).created);

            P2PWrapper list1 = new DbIndexListServerHandler().process(P2PWrapper.build(104, P2PCommand.DB_INDEX_LIST, new DbIndexListRequest(table)));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_LIST, list1.getCommand());
            DbIndexListResponse l1 = (DbIndexListResponse) list1.getData();
            Assertions.assertNotNull(l1.indexes);
            Assertions.assertEquals(1, l1.indexes.size());
            Assertions.assertEquals("username", l1.indexes.get(0).logicalName);
            Assertions.assertEquals("EQ", l1.indexes.get(0).type);
            Assertions.assertTrue(l1.indexes.get(0).colId > 0);

            P2PWrapper info1 = new DbIndexInfoServerHandler().process(P2PWrapper.build(105, P2PCommand.DB_INDEX_INFO, new DbIndexInfoRequest(table, "username")));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_INFO, info1.getCommand());
            DbIndexInfoResponse i1 = (DbIndexInfoResponse) info1.getData();
            Assertions.assertTrue(i1.exists);
            Assertions.assertNotNull(i1.index);
            Assertions.assertEquals("username", i1.index.logicalName);
            Assertions.assertEquals("EQ", i1.index.type);
            Assertions.assertTrue(i1.index.colId > 0);

            P2PWrapper dropResp = new DbIndexDropServerHandler().process(P2PWrapper.build(106, P2PCommand.DB_INDEX_DROP, new DbIndexDropRequest(table, "username")));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_DROP, dropResp.getCommand());
            Assertions.assertTrue(((DbIndexDropResponse) dropResp.getData()).dropped);

            P2PWrapper list2 = new DbIndexListServerHandler().process(P2PWrapper.build(107, P2PCommand.DB_INDEX_LIST, new DbIndexListRequest(table)));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_LIST, list2.getCommand());
            DbIndexListResponse l2 = (DbIndexListResponse) list2.getData();
            Assertions.assertTrue(l2.indexes == null || l2.indexes.isEmpty());

            P2PWrapper info2 = new DbIndexInfoServerHandler().process(P2PWrapper.build(108, P2PCommand.DB_INDEX_INFO, new DbIndexInfoRequest(table, "username")));
            Assertions.assertEquals(P2PCommand.R_OK_DB_INDEX_INFO, info2.getCommand());
            DbIndexInfoResponse i2 = (DbIndexInfoResponse) info2.getData();
            Assertions.assertFalse(i2.exists);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    private static long putRow(String table, String username, int age) throws Exception {
        java.util.List<DbCellValue> values = new java.util.ArrayList<>();
        values.add(new DbCellValue("username", username.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4);
        buf.putInt(age);
        values.add(new DbCellValue("age", buf.array()));
        DbRowPutRequest putRow = new DbRowPutRequest(table, 0L, true, values);
        P2PWrapper putRowResp = new DbRowPutServerHandler().process(P2PWrapper.build(0, P2PCommand.DB_ROW_PUT, putRow));
        Assertions.assertEquals(P2PCommand.R_OK_DB_ROW_PUT, putRowResp.getCommand());
        return ((DbRowPutResponse) putRowResp.getData()).rowId;
    }

    private static long[] queryEq(String table, String name, String value) throws Exception {
        DbQuery q = new DbQuery();
        q.where.add(new DbQueryCriterion(DbQueryOp.EQ, name, value, null, null));
        return queryIds(table, q);
    }

    private static long[] queryIds(String table, DbQuery q) throws Exception {
        DbRowQueryIdsRequest queryReq = new DbRowQueryIdsRequest(table, q, 0, 100);
        P2PWrapper resp = new DbRowQueryIdsServerHandler().process(P2PWrapper.build(0, P2PCommand.DB_ROW_QUERY_IDS, queryReq));
        Assertions.assertEquals(P2PCommand.R_OK_DB_ROW_QUERY_IDS, resp.getCommand());
        DbRowQueryIdsResponse ok = (DbRowQueryIdsResponse) resp.getData();
        return SerializationUtil.deserialize(long[].class, ok.idsBytes);
    }

    private static long countRows(String table, DbQuery q) throws Exception {
        DbRowCountRequest countReq = new DbRowCountRequest(table, q);
        P2PWrapper resp = new DbRowCountServerHandler().process(P2PWrapper.build(0, P2PCommand.DB_ROW_COUNT, countReq));
        Assertions.assertEquals(P2PCommand.R_OK_DB_ROW_COUNT, resp.getCommand());
        DbRowCountResponse ok = (DbRowCountResponse) resp.getData();
        return ok.count;
    }

    private static boolean existsByQuery(String table, DbQuery q) throws Exception {
        DbRowExistsByQueryRequest req = new DbRowExistsByQueryRequest(table, q);
        P2PWrapper resp = new DbRowExistsByQueryServerHandler().process(P2PWrapper.build(0, P2PCommand.DB_ROW_EXISTS_BY_QUERY, req));
        Assertions.assertEquals(P2PCommand.R_OK_DB_ROW_EXISTS_BY_QUERY, resp.getCommand());
        DbRowExistsByQueryResponse ok = (DbRowExistsByQueryResponse) resp.getData();
        return ok.exists;
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }
    
    public static class Profile extends DsTableAdapter {
        @DsField(length = 32)
        private String nick;
        
        public String getNick() {
            return nick;
        }
        
        public void setNick(String nick) {
            this.nick = nick;
        }
    }
    
    public static class UserWithProfile extends DsTableAdapter {
        @DsField(length = 32)
        private String name;
        
        @DsOneToOne(joinProp = "userId")
        private Profile profile;
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public Profile getProfile() {
            return profile;
        }
        
        public void setProfile(Profile profile) {
            this.profile = profile;
        }
    }
}
