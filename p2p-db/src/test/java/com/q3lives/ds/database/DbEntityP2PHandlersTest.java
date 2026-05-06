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
import java.util.Arrays;
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
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.handler.DbEntityExistsServerHandler;
import javax.net.p2p.server.handler.DbEntityGetServerHandler;
import javax.net.p2p.server.handler.DbEntityPutServerHandler;
import javax.net.p2p.server.handler.DbEntityQueryIdsServerHandler;
import javax.net.p2p.server.handler.DbEntityRemoveServerHandler;
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
