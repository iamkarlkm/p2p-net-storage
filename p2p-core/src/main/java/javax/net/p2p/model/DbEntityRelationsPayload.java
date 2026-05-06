package javax.net.p2p.model;

import java.util.List;

public class DbEntityRelationsPayload {
    public List<DbEntityBlob> entities;
    public List<DbEntityRelationField> fields;
    
    public DbEntityRelationsPayload() {
    }
    
    public DbEntityRelationsPayload(List<DbEntityBlob> entities, List<DbEntityRelationField> fields) {
        this.entities = entities;
        this.fields = fields;
    }
}

