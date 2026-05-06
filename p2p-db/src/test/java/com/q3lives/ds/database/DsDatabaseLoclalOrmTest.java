package com.q3lives.ds.database;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsMapField;
import com.q3lives.ds.annotation.DsOneToMany;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.example.UserEntity;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DsDatabaseLoclalOrmTest {
    
    @Test
    public void testYamlDbHomeResolve() throws Exception {
        File base = Files.createTempDirectory("dsdb-yaml").toFile();
        File yaml = new File(base, "SystemConfig.yaml");
        Files.writeString(yaml.toPath(), "DbHome: mydb\n");
        
        String oldYaml = System.getProperty("p2p.system.yaml");
        String oldHome = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.system.yaml", yaml.getAbsolutePath());
            System.clearProperty("p2p.db.home");
            
            DsDatabaseLocal db = DsDatabaseLocal.load();
            Assertions.assertEquals(new File(base, "mydb").getAbsoluteFile(), db.getRoot());
        } finally {
            if (oldYaml == null) {
                System.clearProperty("p2p.system.yaml");
            } else {
                System.setProperty("p2p.system.yaml", oldYaml);
            }
            if (oldHome == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", oldHome);
            }
        }
    }
    
    @Test
    public void testPutGetEntity() throws Exception {
        File base = Files.createTempDirectory("dsdb-basic").toFile();
        DsDatabaseLocal db = new DsDatabaseLocal(base);
        
        UserEntity user = new UserEntity();
        user.setUsername("alice");
        user.setAge(20);
        user.setActive(true);
        user.setUserLevel(7);
        
        long id = db.putTable(user);
        UserEntity loaded = db.getTable(UserEntity.class, id);
        
        Assertions.assertEquals(id, loaded.getId());
        Assertions.assertEquals("alice", loaded.getUsername());
        Assertions.assertEquals(20, loaded.getAge());
        Assertions.assertTrue(loaded.isActive());
        Assertions.assertEquals(7, loaded.getUserLevel());
    }
    
    @Test
    public void testRelationsRoundTrip() throws Exception {
        File base = Files.createTempDirectory("dsdb-rel").toFile();
        DsDatabaseLocal db = new DsDatabaseLocal(base);
        
        Profile profile = new Profile();
        profile.setNick("p1");
        
        Order o1 = new Order();
        o1.setItem("i1");
        Order o2 = new Order();
        o2.setItem("i2");
        
        UserGraph user = new UserGraph();
        user.setName("u1");
        user.setProfile(profile);
        user.setOrders(List.of(o1, o2));
        Map<Profile, Order> fav = new LinkedHashMap<>();
        fav.put(profile, o1);
        user.setFav(fav);
        
        long id = db.putTableWithRelations(user);
        UserGraph loaded = db.getTableWithRelations(UserGraph.class, id);
        
        Assertions.assertEquals("u1", loaded.getName());
        Assertions.assertNotNull(loaded.getProfile());
        Assertions.assertEquals("p1", loaded.getProfile().getNick());
        
        Assertions.assertNotNull(loaded.getOrders());
        Assertions.assertEquals(2, loaded.getOrders().size());
        Assertions.assertEquals("i1", loaded.getOrders().get(0).getItem());
        Assertions.assertEquals("i2", loaded.getOrders().get(1).getItem());
        
        Assertions.assertNotNull(loaded.getFav());
        Assertions.assertEquals(1, loaded.getFav().size());
        Map.Entry<Profile, Order> entry = loaded.getFav().entrySet().iterator().next();
        Assertions.assertEquals("p1", entry.getKey().getNick());
        Assertions.assertEquals("i1", entry.getValue().getItem());
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
    
    public static class Order extends DsTableAdapter {
        @DsField(length = 32)
        private String item;
        
        public String getItem() {
            return item;
        }
        
        public void setItem(String item) {
            this.item = item;
        }
    }
    
    public static class UserGraph extends DsTableAdapter {
        @DsField(length = 32)
        private String name;
        
        @DsOneToOne(joinProp = "userId")
        private Profile profile;
        
        @DsOneToMany(joinClass = Order.class, joinProp = "userId")
        private List<Order> orders;
        
        @DsMapField(keyClass = Profile.class, valueClass = Order.class)
        private Map<Profile, Order> fav;
        
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
        
        public List<Order> getOrders() {
            return orders;
        }
        
        public void setOrders(List<Order> orders) {
            this.orders = orders;
        }
        
        public Map<Profile, Order> getFav() {
            return fav;
        }
        
        public void setFav(Map<Profile, Order> fav) {
            this.fav = fav;
        }
    }
}

