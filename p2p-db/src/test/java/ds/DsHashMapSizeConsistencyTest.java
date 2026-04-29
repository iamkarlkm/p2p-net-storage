package ds;

import com.q3lives.ds.collections.DsHashMap;
import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class DsHashMapSizeConsistencyTest {

    private static void deleteWithSidecars(File f) {
        File[] files = new File[] {
            f,
            new File(f.getAbsolutePath() + ".e16"),
            new File(f.getAbsolutePath() + ".e32"),
            new File(f.getAbsolutePath() + ".e64"),
            new File(f.getAbsolutePath() + ".m32"),
            new File(f.getAbsolutePath() + ".m64")
        };
        for (File x : files) {
            if (x.exists()) {
                x.delete();
            }
        }
    }

    @Test
    public void testSizeLongEqualsIteratorCount() throws Exception {
        File dataFile = new File("test_dshashmap_size_consistency.dat");
        deleteWithSidecars(dataFile);
        DsHashMap map = new DsHashMap(dataFile);

        int count = 20000;
        for (long i = -count; i < count; i++) {
            map.put(i, i);
        }

        long iterCount = 0;
        Set<Long> seen = new HashSet<>();
        Iterator<Map.Entry<Long, Long>> it = map.iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> e = it.next();
            iterCount++;
            // 用 set 兜底检查是否产生重复 key
            seen.add(e.getKey());
        }

        assertEquals("iterator 计数与 set 去重计数必须一致", iterCount, seen.size());
        assertEquals("sizeLong 必须等于 iterator 计数", iterCount, map.sizeLong());
    }
}

