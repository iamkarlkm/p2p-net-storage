package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.database.adapter.DsTableAdapter;

@DsTable(name = "orm_tgt2_user")
public class Orm_P7_Tgt2_UserEntity extends DsTableAdapter {

    @DsField(name = "name", length = 32)
    public String name;

    @DsField(name = "age", length = 4, indexed = true)
    public int age;

    @DsField(name = "createdAt", length = 8)
    public long createdAt;

    public Orm_P7_Tgt2_UserEntity() {}

    public Orm_P7_Tgt2_UserEntity(String name, int age, long createdAt) {
        this.name = name;
        this.age = age;
        this.createdAt = createdAt;
    }
}
