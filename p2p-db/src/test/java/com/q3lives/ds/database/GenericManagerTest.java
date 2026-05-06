package com.q3lives.ds.database;

import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.example.UserEntity;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GenericManagerTest {
    
    @Test
    public void testCrudAndQuery() throws Exception {
        File home = Files.createTempDirectory("dsdb-gm").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            
            GenericManager<UserEntity> m = new GenericManager<>(UserEntity.class);
            
            UserEntity u1 = new UserEntity();
            u1.setUsername("alice");
            u1.setEmail("a@x.com");
            u1.setAge(10);
            m.saveOrUpdate(u1);
            Assertions.assertNotNull(u1.getId());
            Assertions.assertTrue(m.existsById(u1.getId()));
            
            UserEntity u2 = new UserEntity();
            u2.setUsername("bob");
            u2.setEmail("b@x.com");
            u2.setAge(20);
            m.saveOrUpdate(u2);
            
            UserEntity u3 = new UserEntity();
            u3.setUsername("amy");
            u3.setEmail("amy@x.com");
            u3.setAge(15);
            m.saveOrUpdate(u3);
            
            UserEntity loaded = m.getById(u2.getId());
            Assertions.assertEquals("bob", loaded.getUsername());
            Assertions.assertEquals(20, loaded.getAge());
            
            loaded.setAge(21);
            m.updateById(loaded);
            Assertions.assertEquals(21, m.getById(u2.getId()).getAge());
            
            Map<String, Object> conds = new HashMap<>();
            conds.put("username_LikeLeft", "a");
            Assertions.assertEquals(2, m.count(conds));
            List<UserEntity> prefixA = m.findListByMap(conds);
            Assertions.assertEquals(2, prefixA.size());
            
            List<UserEntity> ordered = m.findRangeByPropsWithOrders("age desc", 0, 1, "");
            Assertions.assertEquals(21, ordered.get(0).getAge());
            Assertions.assertEquals(15, ordered.get(1).getAge());
            
            m.removeById(u1.getId());
            Assertions.assertFalse(m.existsById(u1.getId()));
            conds.clear();
            conds.put("username", "alice");
            Assertions.assertEquals(0, m.count(conds));
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }
}

