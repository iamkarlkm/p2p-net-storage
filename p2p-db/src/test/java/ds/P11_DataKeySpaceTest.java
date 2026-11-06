package ds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import com.q3lives.ds.registry.meta.RegistryMetaPaths;

/**
 * P11 T1 DataKeySpaceTest(1)
 * TR-T1-R1: DATA_ROOT + 3 payload helpers 格式断言；
 * TR-T1-R2: grep "/orm/data" src/main/java 仅 RegistryMetaPaths.java 1 处命中（由 c09 Gatekeeper 子断言实锤，此处先断言常量本身存在）
 */
public class P11_DataKeySpaceTest {

    @Test
    public void ac_r1_paths_exist() {
        // DATA_ROOT 必须就是字面量 "/orm/data"（不能拼接，这样 grep 才能命中 1 处声明行）
        assertEquals("/orm/data", RegistryMetaPaths.DATA_ROOT);

        // 三棵子树根必须互不包含，且与 ROW_ROOT / COLUMN_ROOT / DBS_ROOT 前缀无包含关系
        String dataRow = RegistryMetaPaths.DATA_ROW_ROOT;
        String dataCol = RegistryMetaPaths.DATA_COLUMN_ROOT;
        String dataGrp = RegistryMetaPaths.DATA_GROUP_ROOT;
        assertNotNull(dataRow);
        assertNotNull(dataCol);
        assertNotNull(dataGrp);
        assertTrue(dataRow.startsWith(RegistryMetaPaths.DATA_ROOT + "/"));
        assertTrue(dataCol.startsWith(RegistryMetaPaths.DATA_ROOT + "/"));
        assertTrue(dataGrp.startsWith(RegistryMetaPaths.DATA_ROOT + "/"));
        assertFalse(RegistryMetaPaths.ROW_ROOT.startsWith(RegistryMetaPaths.DATA_ROOT));
        assertFalse(RegistryMetaPaths.COLUMN_ROOT.startsWith(RegistryMetaPaths.DATA_ROOT));
        assertFalse(RegistryMetaPaths.DBS_ROOT.startsWith(RegistryMetaPaths.DATA_ROOT));

        // dataRowPayload("com.foo.User", 1234L) 格式断言：段 = data/row + entitySegment(com.foo.User → com/foo/User) + rowIdHex(00000000000004d2)
        long rowId1234 = 1234L;
        String hex4d2 = "00000000000004d2";
        String r1 = RegistryMetaPaths.dataRowPayload("com.foo.User", rowId1234);
        assertTrue(r1, r1.contains("/data/row/"));
        assertTrue(r1, r1.contains("com/foo/User"));
        assertTrue(r1, r1.endsWith(hex4d2));

        // dataColumnPayload 格式断言：段 = data/column + entitySegment + columnSegment(age → age_<hex8>) + hex
        String colSeg = RegistryMetaPaths.columnSegment("age");
        String c1 = RegistryMetaPaths.dataColumnPayload("com.foo.User", "age", rowId1234);
        assertTrue(c1, c1.contains("/data/column/"));
        assertTrue(c1, c1.contains("/" + colSeg + "/"));
        assertTrue(c1, c1.endsWith(hex4d2));

        // dataGroupPayload 格式断言：段 = data/group + entitySegment + groupSegment(cgAgeLen) + hex
        String grpSeg = RegistryMetaPaths.columnSegment("cgAgeLen");
        String g1 = RegistryMetaPaths.dataGroupPayload("com.foo.User", "cgAgeLen", rowId1234);
        assertTrue(g1, g1.contains("/data/group/"));
        assertTrue(g1, g1.contains("/" + grpSeg + "/"));
        assertTrue(g1, g1.endsWith(hex4d2));

        // 边界：rowId 很大（> 2^48），rowIdHex 仍能处理且无超过 16 字符
        long bigRowId = (1L << 54) + 123L;
        String bigHex = RegistryMetaPaths.dataRowPayload("com.foo.Big", bigRowId);
        String last = bigHex.substring(bigHex.lastIndexOf('/') + 1);
        assertTrue("rowIdHex 长度应为 16: last=" + last, last.length() == 16);
        for (int i = 0; i < last.length(); i++) {
            char ch = last.charAt(i);
            assertTrue("rowIdHex 只能小写 hex: " + last,
                (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'));
        }

        // 非法 rowId（≤0） 必须抛 IllegalArgumentException（防止段名 0000000000000000 产生冲突）
        int threw = 0;
        try { RegistryMetaPaths.dataRowPayload("a", 0L); } catch (IllegalArgumentException e) { threw++; }
        try { RegistryMetaPaths.dataRowPayload("a", -1L); } catch (IllegalArgumentException e) { threw++; }
        try { RegistryMetaPaths.dataColumnPayload("a", "c", -5L); } catch (IllegalArgumentException e) { threw++; }
        try { RegistryMetaPaths.dataGroupPayload("a", "g", 0L); } catch (IllegalArgumentException e) { threw++; }
        assertEquals("rowId≤0 应抛 4 次", 4, threw);
    }
}
