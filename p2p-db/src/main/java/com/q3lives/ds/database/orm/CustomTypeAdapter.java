
package com.q3lives.ds.database.orm;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import java.nio.ByteBuffer;

public class CustomTypeAdapter extends DsTableAdapter {
    
    // 自定义类型序列化
    @Override
    public ByteBuffer toBytes() {
        ByteBuffer buffer = super.toBytes();
        
        // 添加自定义类型处理
        // ...
        
        return buffer;
    }
    
    // 自定义类型反序列化
    @Override
    public void load(ByteBuffer data) {
        super.load(data);
        
        // 添加自定义类型处理
        // ...
    }
}
