
package com.q3lives.ds.database;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.config.DsDatabaseClientConfig;
import com.q3lives.ds.database.config.DsDatabaseClientConfigLoader;
import com.q3lives.ds.database.remote.DbEntityRelationsCodec;
import com.q3lives.ds.database.integration.QueryWrapper;
import com.q3lives.ds.util.DsPathUtil;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.net.p2p.client.AbstractP2PClient;
import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.model.DbMetaGetRequest;
import javax.net.p2p.model.DbMetaGetResponse;
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
import javax.net.p2p.model.DbCellValue;
import javax.net.p2p.model.DbRowPutRequest;
import javax.net.p2p.model.DbRowPutResponse;
import javax.net.p2p.model.DbRowGetRequest;
import javax.net.p2p.model.DbRowGetResponse;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbRowQueryIdsRequest;
import javax.net.p2p.model.DbRowQueryIdsResponse;
import javax.net.p2p.model.DbIndexCreateRequest;
import javax.net.p2p.model.DbIndexCreateResponse;
import javax.net.p2p.model.DbIndexDropRequest;
import javax.net.p2p.model.DbIndexDropResponse;
import javax.net.p2p.model.DbIndexDef;
import javax.net.p2p.model.DbIndexListRequest;
import javax.net.p2p.model.DbIndexListResponse;
import javax.net.p2p.model.DbIndexInfoRequest;
import javax.net.p2p.model.DbIndexInfoResponse;
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
import javax.net.p2p.model.DbMetaPutRequest;
import javax.net.p2p.model.DbTableSchema;
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
    private final File localDbRoot;
    
    public DsDatabaseServer(AbstractP2PClient client) {
        this(client, null);
    }
    
    public DsDatabaseServer(AbstractP2PClient client, File localDbRoot) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.localDbRoot = localDbRoot;
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
        File localDbRoot = null;
        if (cfg.local != null && cfg.local.dbHome != null && !cfg.local.dbHome.isBlank()) {
            localDbRoot = new File(cfg.local.dbHome);
        }
        return new DsDatabaseServer(c, localDbRoot);
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

    public <T extends DsTableAdapter> DbMetaGetResponse getMeta(Class<T> entityClass, boolean ensureFresh) throws IOException {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        return getMeta(entityClass.getName(), ensureFresh);
    }

    public DbMetaGetResponse getMeta(String entityClassName, boolean ensureFresh) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        try {
            DbMetaGetRequest payload = new DbMetaGetRequest(entityClassName, ensureFresh, true, true);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_META_GET, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_META_GET) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            if (!(resp.getData() instanceof DbMetaGetResponse ok)) {
                throw new IOException("invalid response payload");
            }
            return ok;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
    
    public <T extends DsTableAdapter> void syncMeta(Class<T> entityClass, boolean ensureFresh) throws IOException {
        if (localDbRoot == null) {
            throw new IllegalStateException("missing local.dbHome in dsdb.yaml");
        }
        DbMetaGetResponse meta = getMeta(entityClass.getName(), ensureFresh);
        if (meta == null) {
            return;
        }
        writeMetaFiles(localDbRoot, entityClass.getName(), meta.tableMetaYaml, meta.columnsMetaYaml);
    }

    public void syncMeta(String entityClassName, boolean ensureFresh) throws IOException {
        if (localDbRoot == null) {
            throw new IllegalStateException("missing local.dbHome in dsdb.yaml");
        }
        DbMetaGetResponse meta = getMeta(entityClassName, ensureFresh);
        if (meta == null) {
            return;
        }
        writeMetaFiles(localDbRoot, entityClassName, meta.tableMetaYaml, meta.columnsMetaYaml);
    }

    public DbMetaGetResponse putMeta(String entityClassName, DbTableSchema schema, boolean overwrite) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        Objects.requireNonNull(schema, "schema cannot be null");
        try {
            DbMetaPutRequest payload = new DbMetaPutRequest(entityClassName, schema, overwrite);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_META_PUT, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_META_PUT) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            if (!(resp.getData() instanceof DbMetaGetResponse ok)) {
                throw new IOException("invalid response payload");
            }
            return ok;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public long allocRow(String entityClassName) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        try {
            DbRowAllocRequest payload = new DbRowAllocRequest(entityClassName);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_ROW_ALLOC, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_ROW_ALLOC || !(resp.getData() instanceof DbRowAllocResponse ok)) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            return ok.rowId;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public boolean rowExists(String entityClassName, long rowId) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (rowId <= 0L) {
            return false;
        }
        try {
            DbRowExistsRequest payload = new DbRowExistsRequest(entityClassName, rowId);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_ROW_EXISTS, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_ROW_EXISTS || !(resp.getData() instanceof DbRowExistsResponse ok)) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            return ok.exists;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public boolean removeRow(String entityClassName, long rowId) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (rowId <= 0L) {
            return false;
        }
        try {
            DbRowRemoveRequest payload = new DbRowRemoveRequest(entityClassName, rowId);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_ROW_REMOVE, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_ROW_REMOVE || !(resp.getData() instanceof DbRowRemoveResponse ok)) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            return ok.removed;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public List<Long> listRowIds(String entityClassName, int offset, int limit) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        try {
            DbRowListIdsRequest payload = new DbRowListIdsRequest(entityClassName, offset, limit);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_ROW_LIST_IDS, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_ROW_LIST_IDS || !(resp.getData() instanceof DbRowListIdsResponse ok) || ok.idsBytes == null) {
                throw new IOException("unexpected response: " + resp.getCommand());
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

    public long putCol(String entityClassName, long rowId, String logicalName, byte[] valueBytes, boolean upsertRow) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (rowId <= 0L) {
            throw new IllegalArgumentException("rowId must be > 0");
        }
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName is blank");
        }
        try {
            DbColPutRequest payload = new DbColPutRequest(entityClassName, rowId, logicalName, valueBytes, upsertRow);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_COL_PUT, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_COL_PUT || !(resp.getData() instanceof DbColPutResponse ok)) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            return ok.valueId;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public byte[] getCol(String entityClassName, long rowId, String logicalName) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (rowId <= 0L) {
            return null;
        }
        if (logicalName == null || logicalName.isBlank()) {
            return null;
        }
        try {
            DbColGetRequest payload = new DbColGetRequest(entityClassName, rowId, logicalName);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_COL_GET, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_COL_GET || !(resp.getData() instanceof DbColGetResponse ok)) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            return ok.found ? ok.valueBytes : null;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public boolean removeCol(String entityClassName, long rowId, String logicalName) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (rowId <= 0L) {
            return false;
        }
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName is blank");
        }
        try {
            DbColRemoveRequest payload = new DbColRemoveRequest(entityClassName, rowId, logicalName);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_COL_REMOVE, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_COL_REMOVE || !(resp.getData() instanceof DbColRemoveResponse ok)) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            return ok.removed;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public long putRow(String entityClassName, long rowId, List<DbCellValue> values, boolean upsertRow) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        try {
            DbRowPutRequest payload = new DbRowPutRequest(entityClassName, rowId, upsertRow, values);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_ROW_PUT, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_ROW_PUT || !(resp.getData() instanceof DbRowPutResponse ok)) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            return ok.rowId;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public List<DbCellValue> getRow(String entityClassName, long rowId, List<String> names) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (rowId <= 0L) {
            return Collections.emptyList();
        }
        try {
            DbRowGetRequest payload = new DbRowGetRequest(entityClassName, rowId, names);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_ROW_GET, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_ROW_GET || !(resp.getData() instanceof DbRowGetResponse ok) || ok.values == null) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            return ok.values;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public List<Long> queryRowIds(String entityClassName, DbQuery query, int offset, int limit) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        try {
            DbRowQueryIdsRequest payload = new DbRowQueryIdsRequest(entityClassName, query, offset, limit);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_ROW_QUERY_IDS, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_ROW_QUERY_IDS || !(resp.getData() instanceof DbRowQueryIdsResponse ok) || ok.idsBytes == null) {
                throw new IOException("unexpected response: " + resp.getCommand());
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

    public boolean createIndex(String entityClassName, String logicalName) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName is blank");
        }
        try {
            DbIndexCreateRequest payload = new DbIndexCreateRequest(entityClassName, logicalName);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_INDEX_CREATE, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_INDEX_CREATE || !(resp.getData() instanceof DbIndexCreateResponse ok)) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            return ok.created;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public boolean dropIndex(String entityClassName, String logicalName) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName is blank");
        }
        try {
            DbIndexDropRequest payload = new DbIndexDropRequest(entityClassName, logicalName);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_INDEX_DROP, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_INDEX_DROP || !(resp.getData() instanceof DbIndexDropResponse ok)) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            return ok.dropped;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public List<DbIndexDef> listIndexes(String entityClassName) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        try {
            DbIndexListRequest payload = new DbIndexListRequest(entityClassName);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_INDEX_LIST, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_INDEX_LIST || !(resp.getData() instanceof DbIndexListResponse ok)) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            if (ok.indexes == null || ok.indexes.isEmpty()) {
                return Collections.emptyList();
            }
            return ok.indexes;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public DbIndexDef getIndexInfo(String entityClassName, String logicalName) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName is blank");
        }
        try {
            DbIndexInfoRequest payload = new DbIndexInfoRequest(entityClassName, logicalName);
            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.DB_INDEX_INFO, payload));
            if (resp.getCommand() == P2PCommand.STD_ERROR) {
                throw P2PErrors.asRuntimeException(resp);
            }
            if (resp.getCommand() != P2PCommand.R_OK_DB_INDEX_INFO || !(resp.getData() instanceof DbIndexInfoResponse ok)) {
                throw new IOException("unexpected response: " + resp.getCommand());
            }
            if (!ok.exists || ok.index == null) {
                return null;
            }
            return ok.index;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
    
    private static void writeMetaFiles(File dbRoot, String entityClassName, byte[] tableMetaYaml, byte[] columnsMetaYaml) throws IOException {
        String spacePath = DsPathUtil.dottedToLinuxPath(entityClassName, "entityClass");
        File dir = new File(dbRoot, "indexes/" + spacePath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        if (tableMetaYaml != null) {
            Files.write(new File(dir, "table.meta.yaml").toPath(), tableMetaYaml);
        }
        if (columnsMetaYaml != null) {
            Files.write(new File(dir, "columns.meta.yaml").toPath(), columnsMetaYaml);
        }
    }
    
    @Override
    public void close() {
        client.close();
    }
}
