package com.q3lives.ds.registry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import com.q3lives.ds.enums.Category;
import com.q3lives.ds.enums.DataType;

public final class DsRegistryExtAttrsHelper {

    private static final String TAGS_DIR = "/@tags";
    private static final String META_DIR = "/@meta";
    private static final String DESCS = META_DIR + "/descs";
    private static final String SPECS = META_DIR + "/specs";
    private static final String COMMENTS = META_DIR + "/comments";
    private static final String SEQ_DIR = "/_extNextSeq";
    private static final String SEQ_TAGS = "/tags_seq";
    private static final String SEQ_DESCS = "/descs_seq";
    private static final String SEQ_SPECS = "/specs_seq";
    private static final String SEQ_COMMENTS = "/comments_seq";

    private DsRegistryExtAttrsHelper() {}

    private static String normalizeEntry(String entryKey) {
        if (entryKey == null || entryKey.isEmpty() || "/".equals(entryKey)) {
            throw new IllegalArgumentException("entryKey 不能为空或根目录，必须是 /xxx/yyy 叶子或中间节点路径");
        }
        if (entryKey.charAt(0) != '/') entryKey = "/" + entryKey;
        while (entryKey.length() > 1 && entryKey.endsWith("/")) {
            entryKey = entryKey.substring(0, entryKey.length() - 1);
        }
        return entryKey;
    }

    private static long nextSeq(DsRegistry r, String entryKey, String seqSubKey) throws IOException {
        String seqKey = entryKey + SEQ_DIR + seqSubKey;
        long current = 0L;
        RegistryEntry e = r.get(seqKey);
        if (e.isPresent() && e.category() == Category.NUMERIC) current = e.asLong();
        long next = current + 1L;
        r.putLong(seqKey, DataType.REG_DWORD, next);
        return next;
    }

    private static void resetSeq(DsRegistry r, String entryKey, String seqSubKey) throws IOException {
        String seqKey = entryKey + SEQ_DIR + seqSubKey;
        r.putLong(seqKey, DataType.REG_DWORD, 0L);
    }

    private static long getSeq(DsRegistry r, String entryKey, String seqSubKey) throws IOException {
        String seqKey = entryKey + SEQ_DIR + seqSubKey;
        RegistryEntry e = r.get(seqKey);
        if (e.isPresent() && e.category() == Category.NUMERIC) return e.asLong();
        return 0L;
    }

    /* ========================================================================
     * TAG 相关：@tags/<tagName> 叶子 REG_SZ（空串标记存在），一对多幂等
     * ====================================================================== */
    public static boolean addTag(DsRegistry r, String entryKey, String tag) throws IOException {
        if (r == null) throw new IllegalArgumentException("r is null");
        if (tag == null || tag.isEmpty()) throw new IllegalArgumentException("tag 不能为空");
        String ek = normalizeEntry(entryKey);
        r.mkdirs(ek + TAGS_DIR);
        String tagKey = ek + TAGS_DIR + "/" + tag;
        if (r.exists(tagKey)) return false;
        r.putString(tagKey, "", DataType.REG_SZ);
        return true;
    }

    public static boolean addTags(DsRegistry r, String entryKey, Iterable<String> tags) throws IOException {
        if (tags == null) return false;
        boolean anyAdded = false;
        for (String t : tags) if (addTag(r, entryKey, t)) anyAdded = true;
        return anyAdded;
    }

    public static boolean removeTag(DsRegistry r, String entryKey, String tag) throws IOException {
        if (r == null) throw new IllegalArgumentException("r is null");
        if (tag == null || tag.isEmpty()) return false;
        String ek = normalizeEntry(entryKey);
        String tagKey = ek + TAGS_DIR + "/" + tag;
        if (!r.exists(tagKey)) return false;
        return r.remove(tagKey, false);
    }

    public static int removeTags(DsRegistry r, String entryKey, Iterable<String> tags) throws IOException {
        if (tags == null) return 0;
        int removed = 0;
        for (String t : tags) if (removeTag(r, entryKey, t)) removed++;
        return removed;
    }

    public static int removeAllTags(DsRegistry r, String entryKey) throws IOException {
        if (r == null) throw new IllegalArgumentException("r is null");
        String ek = normalizeEntry(entryKey);
        String dirKey = ek + TAGS_DIR;
        RegistryEntry dir = r.get(dirKey);
        if (!dir.isPresent() || !dir.isDirectory()) { resetSeq(r, ek, SEQ_TAGS); return 0; }
        List<String> children = r.listChildren(dirKey);
        int n = 0;
        for (String c : children) if (r.remove(dirKey + "/" + c, false)) n++;
        resetSeq(r, ek, SEQ_TAGS);
        return n;
    }

    public static LinkedHashSet<String> listTags(DsRegistry r, String entryKey) throws IOException {
        return listTags(r, entryKey, Integer.MAX_VALUE, null);
    }

    public static LinkedHashSet<String> listTags(DsRegistry r, String entryKey, int limit, java.util.function.Predicate<String> tagFilter) throws IOException {
        if (r == null) throw new IllegalArgumentException("r is null");
        String ek = normalizeEntry(entryKey);
        String dirKey = ek + TAGS_DIR;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        RegistryEntry dir = r.get(dirKey);
        if (!dir.isPresent() || !dir.isDirectory()) return out;
        List<String> list = r.listChildren(dirKey);
        Collections.sort(list);
        int max = (limit <= 0) ? Integer.MAX_VALUE : limit;
        for (String name : list) {
            if (tagFilter != null && !tagFilter.test(name)) continue;
            out.add(name);
            if (out.size() >= max) break;
        }
        return out;
    }

    public static boolean hasTag(DsRegistry r, String entryKey, String tag) throws IOException {
        if (r == null || tag == null || tag.isEmpty()) return false;
        String ek = normalizeEntry(entryKey);
        return r.exists(ek + TAGS_DIR + "/" + tag);
    }

    /* ========================================================================
     * DESC / SPEC / COMMENT：多版本按存储顺序，@meta/descs|<N> 数字
     * ====================================================================== */
    public static long addDesc(DsRegistry r, String entryKey, String descText) throws IOException {
        return addMetaItem(r, entryKey, DESCS, SEQ_DESCS, descText);
    }

    public static List<String> listDescs(DsRegistry r, String entryKey) throws IOException {
        return listMetaItems(r, entryKey, DESCS);
    }

    public static String lastDesc(DsRegistry r, String entryKey) throws IOException {
        List<String> l = listDescs(r, entryKey);
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    public static boolean removeLastDesc(DsRegistry r, String entryKey) throws IOException {
        return removeLastMetaItem(r, entryKey, DESCS);
    }

    public static int removeAllDescs(DsRegistry r, String entryKey) throws IOException {
        return removeAllMetaItems(r, entryKey, DESCS, SEQ_DESCS);
    }

    public static long addSpec(DsRegistry r, String entryKey, String specText) throws IOException {
        return addMetaItem(r, entryKey, SPECS, SEQ_SPECS, specText);
    }

    public static List<String> listSpecs(DsRegistry r, String entryKey) throws IOException {
        return listMetaItems(r, entryKey, SPECS);
    }

    public static String lastSpec(DsRegistry r, String entryKey) throws IOException {
        List<String> l = listSpecs(r, entryKey);
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    public static boolean removeLastSpec(DsRegistry r, String entryKey) throws IOException {
        return removeLastMetaItem(r, entryKey, SPECS);
    }

    public static int removeAllSpecs(DsRegistry r, String entryKey) throws IOException {
        return removeAllMetaItems(r, entryKey, SPECS, SEQ_SPECS);
    }

    public static long addComment(DsRegistry r, String entryKey, String commentText) throws IOException {
        return addMetaItem(r, entryKey, COMMENTS, SEQ_COMMENTS, commentText);
    }

    public static List<String> listComments(DsRegistry r, String entryKey) throws IOException {
        return listMetaItems(r, entryKey, COMMENTS);
    }

    public static String lastComment(DsRegistry r, String entryKey) throws IOException {
        List<String> l = listComments(r, entryKey);
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    public static boolean removeLastComment(DsRegistry r, String entryKey) throws IOException {
        return removeLastMetaItem(r, entryKey, COMMENTS);
    }

    public static int removeAllComments(DsRegistry r, String entryKey) throws IOException {
        return removeAllMetaItems(r, entryKey, COMMENTS, SEQ_COMMENTS);
    }

    /* ===== Private: META 三列共享 helpers ===== */

    private static long addMetaItem(DsRegistry r, String entryKey, String subDir, String seqKey, String text) throws IOException {
        if (r == null) throw new IllegalArgumentException("r is null");
        if (text == null) text = "";
        String ek = normalizeEntry(entryKey);
        r.mkdirs(ek + subDir);
        long seq = nextSeq(r, ek, seqKey);
        String itemKey = ek + subDir + "/" + seq;
        r.putString(itemKey, text, DataType.REG_SZ);
        return seq;
    }

    private static List<String> listMetaItems(DsRegistry r, String entryKey, String subDir) throws IOException {
        if (r == null) throw new IllegalArgumentException("r is null");
        String ek = normalizeEntry(entryKey);
        String dirKey = ek + subDir;
        ArrayList<String> out = new ArrayList<>();
        RegistryEntry dir = r.get(dirKey);
        if (!dir.isPresent() || !dir.isDirectory()) return out;
        List<String> children = r.listChildren(dirKey);
        ArrayList<Long> sorted = new ArrayList<>(children.size());
        for (String c : children) {
            try { sorted.add(Long.parseLong(c)); } catch (NumberFormatException ignore) {}
        }
        Collections.sort(sorted);
        for (Long seq : sorted) {
            String itemKey = dirKey + "/" + seq;
            RegistryEntry ie = r.get(itemKey);
            if (ie.isPresent() && ie.category() == Category.STRING) out.add(ie.asString());
        }
        out.trimToSize();
        return out;
    }

    private static boolean removeLastMetaItem(DsRegistry r, String entryKey, String subDir) throws IOException {
        if (r == null) throw new IllegalArgumentException("r is null");
        String ek = normalizeEntry(entryKey);
        String dirKey = ek + subDir;
        RegistryEntry dir = r.get(dirKey);
        if (!dir.isPresent() || !dir.isDirectory()) return false;
        List<String> children = r.listChildren(dirKey);
        long maxSeq = -1L;
        for (String c : children) {
            try { long v = Long.parseLong(c); if (v > maxSeq) maxSeq = v; } catch (NumberFormatException ignore) {}
        }
        if (maxSeq < 1L) return false;
        String lastKey = dirKey + "/" + maxSeq;
        if (!r.exists(lastKey)) return false;
        return r.remove(lastKey, false);
    }

    private static int removeAllMetaItems(DsRegistry r, String entryKey, String subDir, String seqKey) throws IOException {
        if (r == null) throw new IllegalArgumentException("r is null");
        String ek = normalizeEntry(entryKey);
        String dirKey = ek + subDir;
        RegistryEntry dir = r.get(dirKey);
        if (!dir.isPresent() || !dir.isDirectory()) { resetSeq(r, ek, seqKey); return 0; }
        List<String> children = r.listChildren(dirKey);
        int n = 0;
        for (String c : children) {
            String childKey = dirKey + "/" + c;
            if (r.remove(childKey, false)) n++;
        }
        resetSeq(r, ek, seqKey);
        return n;
    }
}
