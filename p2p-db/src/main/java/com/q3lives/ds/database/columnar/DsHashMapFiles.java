package com.q3lives.ds.database.columnar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class DsHashMapFiles {
    private DsHashMapFiles() {
    }

    static void deleteAll(File base) {
        if (base == null) {
            return;
        }
        for (File f : allFiles(base)) {
            try {
                if (f.exists()) {
                    f.delete();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static List<File> allFiles(File base) {
        List<File> out = new ArrayList<>(12);
        out.add(base);

        File e16 = new File(base.getAbsolutePath() + ".e16");
        out.add(e16);
        out.add(new File(e16.getAbsolutePath() + ".free"));

        File m32 = new File(base.getAbsolutePath() + ".m32");
        out.add(m32);
        File e32 = new File(base.getAbsolutePath() + ".e32");
        out.add(e32);
        out.add(new File(e32.getAbsolutePath() + ".free"));

        File m64 = new File(base.getAbsolutePath() + ".m64");
        out.add(m64);
        File e64 = new File(base.getAbsolutePath() + ".e64");
        out.add(e64);
        out.add(new File(e64.getAbsolutePath() + ".free"));

        File e16Tmp = new File(e16.getAbsolutePath() + ".tmp");
        File e32Tmp = new File(e32.getAbsolutePath() + ".tmp");
        File e64Tmp = new File(e64.getAbsolutePath() + ".tmp");
        out.add(e16Tmp);
        out.add(e32Tmp);
        out.add(e64Tmp);

        return out;
    }
}

