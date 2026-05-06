package javax.net.p2p.model;

public class DbEntityRelationField {
    public String fieldName;
    public String kind;
    public int[] indices;
    
    public DbEntityRelationField() {
    }
    
    public DbEntityRelationField(String fieldName, String kind, int[] indices) {
        this.fieldName = fieldName;
        this.kind = kind;
        this.indices = indices;
    }
}

