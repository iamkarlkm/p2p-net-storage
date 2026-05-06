package com.q3lives.ds.database.columnar;

import com.q3lives.ds.annotation.DsCompositeField;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.exception.meta.MetaCompositeBitOverlapException;
import com.q3lives.ds.exception.meta.MetaCompositeGroupLengthMismatchException;
import com.q3lives.ds.exception.meta.MetaDuplicateColumnDefinitionException;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TableMetaValidationTest {
    @Test
    public void testDuplicateDsFieldNameMismatchRejected() throws Exception {
        File home = Files.createTempDirectory("dsdb-meta-dup-field").toFile();
        TableMetaStore store = new TableMetaStore(home);
        MetaDuplicateColumnDefinitionException ex =
            Assertions.assertThrows(MetaDuplicateColumnDefinitionException.class, () -> store.ensureMeta(DupFieldRow.class));
        Assertions.assertTrue(ex.getMessage().contains("duplicate DsField name"));
    }

    @Test
    public void testCompositeGroupLengthMismatchRejected() throws Exception {
        File home = Files.createTempDirectory("dsdb-meta-comp-len").toFile();
        TableMetaStore store = new TableMetaStore(home);
        MetaCompositeGroupLengthMismatchException ex =
            Assertions.assertThrows(MetaCompositeGroupLengthMismatchException.class, () -> store.ensureMeta(CompositeLenMismatchRow.class));
        Assertions.assertTrue(ex.getMessage().contains("length mismatch"));
    }

    @Test
    public void testCompositeBitOverlapRejected() throws Exception {
        File home = Files.createTempDirectory("dsdb-meta-comp-overlap").toFile();
        TableMetaStore store = new TableMetaStore(home);
        MetaCompositeBitOverlapException ex =
            Assertions.assertThrows(MetaCompositeBitOverlapException.class, () -> store.ensureMeta(CompositeOverlapRow.class));
        Assertions.assertTrue(ex.getMessage().contains("overlap"));
    }

    public static class BaseRow extends DsTableAdapter {
        @DsField(name = "dup", length = 8)
        private String a;
    }

    public static class DupFieldRow extends BaseRow {
        @DsField(name = "dup", length = 16)
        private String b;
    }

    public static class CompositeLenMismatchRow extends DsTableAdapter {
        @DsCompositeField(name = "a", group = "G", length = 8, startBits = 0, endBits = 0)
        private boolean a;

        @DsCompositeField(name = "b", group = "G", length = 4, startBits = 1, endBits = 1)
        private boolean b;
    }

    public static class CompositeOverlapRow extends DsTableAdapter {
        @DsCompositeField(name = "a", group = "G", length = 8, startBits = 0, endBits = 3)
        private int a;

        @DsCompositeField(name = "b", group = "G", length = 8, startBits = 3, endBits = 5)
        private int b;
    }
}
