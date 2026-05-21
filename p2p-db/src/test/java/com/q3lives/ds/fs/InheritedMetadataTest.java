package com.q3lives.ds.fs;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InheritedMetadata 序列化测试。
 */
class InheritedMetadataTest {

    @Test
    void testToBytesAndLoadRoundTrip() {
        InheritedMetadata original = new InheritedMetadata();
        original.setId(12345L);
        original.flags = 0x0007;
        original.gid = 100;
        original.uid = 1000;
        original.roleId = 5;
        original.acls = new HashSet<>();
        original.acls.add(1001L);
        original.acls.add(2002L);
        original.acls.add(3003L);

        ByteBuffer buffer = original.toBytes();
        assertNotNull(buffer);
        assertEquals(28 + 3 * 8, buffer.remaining());

        InheritedMetadata loaded = new InheritedMetadata();
        loaded.load(buffer);

        assertEquals(12345L, loaded.getId());
        assertEquals(0x0007, loaded.flags);
        assertEquals(100, loaded.gid);
        assertEquals(1000, loaded.uid);
        assertEquals(5, loaded.roleId);
        assertNotNull(loaded.acls);
        assertEquals(3, loaded.acls.size());
        assertTrue(loaded.acls.contains(1001L));
        assertTrue(loaded.acls.contains(2002L));
        assertTrue(loaded.acls.contains(3003L));
    }

    @Test
    void testEmptyAcls() {
        InheritedMetadata original = new InheritedMetadata();
        original.setId(1L);
        original.flags = 0;
        original.gid = 0;
        original.uid = 0;
        original.roleId = 0;
        original.acls = new HashSet<>();

        ByteBuffer buffer = original.toBytes();
        assertEquals(28, buffer.remaining());

        InheritedMetadata loaded = new InheritedMetadata();
        loaded.load(buffer);
        assertNotNull(loaded.acls);
        assertTrue(loaded.acls.isEmpty());
    }

    @Test
    void testNullAcls() {
        InheritedMetadata original = new InheritedMetadata();
        original.setId(99L);
        original.acls = null;

        ByteBuffer buffer = original.toBytes();
        assertEquals(28, buffer.remaining());

        InheritedMetadata loaded = new InheritedMetadata();
        loaded.load(buffer);
        assertNotNull(loaded.acls);
        assertTrue(loaded.acls.isEmpty());
    }

    @Test
    void testLoadTooSmallData() {
        InheritedMetadata loaded = new InheritedMetadata();
        ByteBuffer small = ByteBuffer.allocate(10);
        assertThrows(IllegalArgumentException.class, () -> loaded.load(small));
    }
}
