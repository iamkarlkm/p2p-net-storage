
package com.q3lives.ds.collections;

import com.q3lives.ds.example.UserEntity;
import com.q3lives.ds.validator.EntityValidator;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DsTableAdapterTest {
    
    @Test
    public void testBasicSerialization() {
        // 创建实体
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("test");
        user.setAge(25);
        
        // 序列化
        ByteBuffer buffer = user.toBytes();
        assertNotNull(buffer);
        assertTrue(buffer.limit() > 0);
        
        // 反序列化
        UserEntity loaded = new UserEntity();
        loaded.load(buffer);
        
        // 验证
        assertEquals(user.getId(), loaded.getId());
        assertEquals(user.getUsername(), loaded.getUsername());
        assertEquals(user.getAge(), loaded.getAge());
    }
    
    @Test
    public void testCompositeFields() {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setActive(true);
        user.setVerified(false);
        user.setUserLevel(5);
        user.setUserType(2);
        
        ByteBuffer buffer = user.toBytes();
        UserEntity loaded = new UserEntity();
        loaded.load(buffer);
        
        assertEquals(user.isActive(), loaded.isActive());
        assertEquals(user.isVerified(), loaded.isVerified());
        assertEquals(user.getUserLevel(), loaded.getUserLevel());
        assertEquals(user.getUserType(), loaded.getUserType());
    }
    
    @Test
    public void testValidation() {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setAge(-1); // 无效年龄
        
        EntityValidator.ValidationResult result = EntityValidator.validate(user);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().size() > 0);
    }
}
