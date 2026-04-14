package ds;

import com.q3lives.ds.util.DsPathUtil;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsPathUtilTest {

    @Test
    public void testNormalizeAbsolute() {
        assertEquals("/a/b/c", DsPathUtil.normalizeLinuxPath("/a/b/c", true));
        assertEquals("/", DsPathUtil.normalizeLinuxPath("/", true));
        assertEquals("/a/b", DsPathUtil.normalizeLinuxPath("/a/b/", true));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectBackslash() {
        DsPathUtil.normalizeLinuxPath("\\a\\b", false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectDotSegment() {
        DsPathUtil.normalizeLinuxPath("/a/./b", true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectDotDotSegment() {
        DsPathUtil.normalizeLinuxPath("/a/../b", true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testResolveRelativeRequiresParent() {
        DsPathUtil.resolveLinuxPath(null, "a/b");
    }

    @Test
    public void testResolve() {
        assertEquals("/p/a/b", DsPathUtil.resolveLinuxPath("/p", "a/b"));
        assertEquals("/a/b", DsPathUtil.resolveLinuxPath("/p", "/a/b"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDottedRejectEmpty() {
        DsPathUtil.dottedToLinuxPath("a..b", "space");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDottedRejectTraversal() {
        DsPathUtil.dottedToLinuxPath("a...b", "space");
    }
}
