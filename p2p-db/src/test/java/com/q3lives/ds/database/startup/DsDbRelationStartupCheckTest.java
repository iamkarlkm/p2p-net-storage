package com.q3lives.ds.database.startup;

import com.q3lives.ds.annotation.DsOneToMany;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DsDbRelationStartupCheckTest {
    @Test
    public void enabledShouldFailOnInvalidRelationFieldType() throws Exception {
        Path dir = Files.createTempDirectory("dsdb-startup-check");
        Path yaml = dir.resolve("SystemConfig.yaml");
        Files.writeString(
            yaml,
            ""
                + "DsDbStartupCheck:\n"
                + "  enabled: true\n"
                + "  strict: true\n"
                + "  entityClasses:\n"
                + "    - " + BadEntity.class.getName() + "\n",
            StandardCharsets.UTF_8
        );
        String old = System.getProperty("p2p.system.yaml");
        try {
            System.setProperty("p2p.system.yaml", yaml.toString());
            DsDbRelationStartupCheck check = new DsDbRelationStartupCheck();
            IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class, check::check);
            Assertions.assertTrue(ex.getMessage().contains("BadEntity"));
        } finally {
            if (old == null) {
                System.clearProperty("p2p.system.yaml");
            } else {
                System.setProperty("p2p.system.yaml", old);
            }
        }
    }

    public static final class BadEntity extends DsTableAdapter {
        @DsOneToMany(joinClass = GoodEntity.class, joinProp = "id")
        public Set<GoodEntity> children;
    }

    public static final class GoodEntity extends DsTableAdapter {
        @DsOneToOne(joinProp = "id")
        public GoodEntity self;
    }
}
