
package com.q3lives.ds.database;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.config.DsDatabaseClientConfig;
import com.q3lives.ds.database.config.DsDatabaseClientConfigLoader;
import com.q3lives.ds.database.remote.DbEntityRelationsCodec;
import com.q3lives.ds.database.integration.QueryWrapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.net.p2p.client.AbstractP2PClient;
import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.model.DbEntityGetRequest;
import javax.net.p2p.model.DbEntityGetResponse;
import javax.net.p2p.model.DbEntityExistsRequest;
import javax.net.p2p.model.DbEntityExistsResponse;
import javax.net.p2p.model.DbEntityPutRequest;
import javax.net.p2p.model.DbEntityPutResponse;
import javax.net.p2p.model.DbEntityQueryIdsRequest;
import javax.net.p2p.model.DbEntityQueryIdsResponse;
import javax.net.p2p.model.DbEntityRemoveRequest;
import javax.net.p2p.model.DbEntityRemoveResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.utils.SerializationUtil;
import org.yaml.snakeyaml.Yaml;

/**
 *
 * @author Administrator
 */
public class DsDatabaseServer implements AutoCloseable {
    
    private final AbstractP2PClient client;
    
    public DsDatabaseServer(AbstractP2PClient client) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
    }
    
    public static DsDatabaseServer load() {
        DsDatabaseClientConfigLoader.LoadedConfig loaded = DsDatabaseClientConfigLoader.load();
        DsDatabaseClientConfig cfg = loaded.config;
        if (cfg == null || cfg.server == null) {
            throw new IllegalArgumentException("missing server config");
        }
        if (cfg.mode != null && !cfg.mode.isBlank() && !"server".equalsIgnoreCase(cfg.mode)) {
            throw new IllegalArgumentException("dsdb.yaml mode is not server");
        }
        if (cfg.server.ip == null || cfg.server.ip.isBlank()) {
            throw new IllegalArgumentException("missing server.ip");
        }
        if (cfg.server.port <= 0) {
            throw new IllegalArgumentException("missing server.port");
        }
        
        if (cfg.server.auth != null && cfg.server.auth.isEnabled()) {
            Yaml yaml = new Yaml();
            String inline = yaml.dumpAsMap(cfg.server.auth);
            System.setProperty("p2p.auth.inlineYaml", inline);
            if (loaded.yamlFile.getParentFile() != null) {
                System.setProperty("p2p.auth.inlineBaseDir", loaded.yamlFile.getParentFile().getAbsolutePath());
            }
        }
        
        P2PClientTcp c = new P2PClientTcp(new InetSocketAddress(cfg.server.ip, cfg.server.port));
        try {
            if (cfg.server.auth != null && cfg.server.auth.isEnabled()) {
                c.handshake();
                c.login();
            }
        } catch (Exception e) {
            c.close();
            throw new RuntimeException(e);
        }
        return new DsDatabaseServer(c);
    }
    
    /**
     * 存储DsTableAdapter
     * @param value
     * @return
     * @throws IOException 
     */
    public long putTable(DsTableAdapter value) throws IOException {
        return putTable(value, false);
    }
    
    public long putTable(DsTableAdapter value, boolean withRelations) throws IOException {
        Objects.requireNonNull(value, "value cannot be null");
        ByteBuffer buf = value.toBytes();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);

        byte[] relations = withRelations ? DbEntityRelationsCodec.encode(value) : null;
        DbEntityPutRequest payload = new DbEntityPutRequest(value.getClass().getName(), bytes, withRelations, relations);
        try {
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_ENTITY_PUT, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_ENTITY_PUT) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            if (!(resp.getData() instanceof DbEntityPutResponse ok)) {
                throw new IOException("invalid response payload");
            }
            return ok.id;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
    
    public long putTableWithRelations(DsTableAdapter value) throws IOException {
        return putTable(value, true);
    }
    
    public <T extends DsTableAdapter> T getTable(Class<T> clazz, long id) throws IOException {
        return getTable(clazz, id, false);
    }
    
    public <T extends DsTableAdapter> T getTable(Class<T> clazz, long id, boolean withRelations) throws IOException {
        try {
            DbEntityGetRequest payload = new DbEntityGetRequest(clazz.getName(), id, withRelations);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_ENTITY_GET, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_ENTITY_GET) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            if (!(resp.getData() instanceof DbEntityGetResponse ok) || ok.bytes == null) {
                throw new IOException("invalid response payload");
            }
            T obj = clazz.getDeclaredConstructor().newInstance();
            obj.load(ByteBuffer.wrap(ok.bytes));
            if (withRelations && ok.relations != null && ok.relations.length > 0) {
                DbEntityRelationsCodec.apply(obj, ok.relations);
            }
            return obj;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public <T extends DsTableAdapter> T getTableWithRelations(Class<T> clazz, long id) throws IOException {
        return getTable(clazz, id, true);
    }

    public <T extends DsTableAdapter> boolean exists(Class<T> clazz, long id) throws IOException {
        try {
            DbEntityExistsRequest payload = new DbEntityExistsRequest(clazz.getName(), id);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_ENTITY_EXISTS, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_ENTITY_EXISTS) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            if (!(resp.getData() instanceof DbEntityExistsResponse ok)) {
                throw new IOException("invalid response payload");
            }
            return ok.exists;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public <T extends DsTableAdapter> boolean removeTable(Class<T> clazz, long id, boolean withRelations) throws IOException {
        try {
            DbEntityRemoveRequest payload = new DbEntityRemoveRequest(clazz.getName(), id, withRelations);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_ENTITY_REMOVE, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_ENTITY_REMOVE) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            if (!(resp.getData() instanceof DbEntityRemoveResponse ok)) {
                throw new IOException("invalid response payload");
            }
            return ok.removed;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public <T extends DsTableAdapter> boolean removeTableWithRelations(Class<T> clazz, long id) throws IOException {
        return removeTable(clazz, id, true);
    }

    public <T extends DsTableAdapter> List<Long> queryIds(Class<T> clazz, QueryWrapper<?> wrapper, int start, int end) throws IOException {
        Objects.requireNonNull(wrapper, "wrapper cannot be null");
        try {
            byte[] wrapperBytes = SerializationUtil.serialize(wrapper);
            DbEntityQueryIdsRequest payload = new DbEntityQueryIdsRequest(clazz.getName(), wrapperBytes, start, end);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_ENTITY_QUERY_IDS, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_ENTITY_QUERY_IDS) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            if (!(resp.getData() instanceof DbEntityQueryIdsResponse ok) || ok.idsBytes == null) {
                throw new IOException("invalid response payload");
            }
            long[] ids = SerializationUtil.deserialize(long[].class, ok.idsBytes);
            if (ids == null || ids.length == 0) {
                return Collections.emptyList();
            }
            List<Long> out = new java.util.ArrayList<>(ids.length);
            for (long v : ids) {
                if (v > 0L) {
                    out.add(v);
                }
            }
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
    
    @Override
    public void close() {
        client.close();
    }
}
