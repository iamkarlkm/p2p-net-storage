
package com.q3lives.ds.database;

import com.q3lives.ds.annotation.DsProp;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author Administrator
 */
public class DsDatabaseStruct {
       
    @DsProp()
    public String space;//存储目录。
    @DsProp()
    public String name;//数据库名
    
    @DsProp()
    public String comment;//注释
   
    @DsProp()
    private Map<String,DsTableStruct> tables = new HashMap();

  
    public Map<String, DsTableStruct> getTables() {
        return tables;
    }

    public String getSpace() {
        return space;
    }

    public void setSpace(String space) {
        this.space = space;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
    
    
}
