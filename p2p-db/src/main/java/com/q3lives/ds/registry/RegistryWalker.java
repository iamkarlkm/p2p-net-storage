package com.q3lives.ds.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.q3lives.ds.enums.DataType;

/**
 * DsRegistry 遍历 visitor 与 4 个常用实现。
 */
public final class RegistryWalker {

    @FunctionalInterface
    public interface RegistryEntryVisitor {
        /**
         * 访问一个节点。
         * @return true 表示继续遍历子节点或后续兄弟；false 表示停止（对 DIR = 不进子树；对 LEAF = 不再继续列表）。
         */
        boolean onEntry(RegistryEntry entry, int depth) throws Exception;
    }

    public static final class DumpAllVisitor implements RegistryEntryVisitor {
        private final StringBuilder out = new StringBuilder();
        @Override public boolean onEntry(RegistryEntry entry, int depth) {
            for (int i = 0; i < depth; i++) out.append("  ");
            out.append(entry.isDirectory() ? "[DIR] " : "[LEAF] ").append(entry.key())
                    .append(" (").append(entry.dataType().name()).append(") ");
            if (entry.inlineValueRaw().isPresent()) {
                out.append(" inline=").append(entry.inlineValueRaw().getAsLong());
            }
            if (entry.stringValue().isPresent()) {
                String s = entry.stringValue().get();
                if (s.length() > 80) s = s.substring(0, 77) + "...";
                out.append(" str=\"").append(s).append("\"");
            }
            out.append('\n');
            return true;
        }
        public String toString() { return out.toString(); }
    }

    public static final class ToMapVisitor implements RegistryEntryVisitor {
        private final Map<String, Object> map = new LinkedHashMap<>();
        @Override public boolean onEntry(RegistryEntry entry, int depth) {
            String k = entry.key() == null ? "" : entry.key();
            switch (entry.dataType().getCategory()) {
                case NUMERIC:
                    try { map.put(k, entry.asLong()); } catch (Exception ignore) { map.put(k, null); }
                    break;
                case STRING:
                case CONFIG:
                case SCRIPT:
                case DOCS:
                case CODE:
                    map.put(k, entry.asString());
                    break;
                default:
                    map.put(k, entry.asBytes());
            }
            return true;
        }
        public Map<String, Object> asMap() { return map; }
    }

    public static final class FilterByDataTypeVisitor implements RegistryEntryVisitor {
        private final Predicate<DataType> match;
        private final List<RegistryEntry> matched = new ArrayList<>();
        public FilterByDataTypeVisitor(Predicate<DataType> match) { this.match = match; }
        @Override public boolean onEntry(RegistryEntry entry, int depth) {
            if (match.test(entry.dataType())) matched.add(entry);
            return true;
        }
        public List<RegistryEntry> matched() { return matched; }
    }

    public static final class CountVisitor implements RegistryEntryVisitor {
        private long total;
        @Override public boolean onEntry(RegistryEntry entry, int depth) {
            total++; return true;
        }
        public long count() { return total; }
    }

    private RegistryWalker() {}
}
