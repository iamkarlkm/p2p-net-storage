package com.q3lives.ds.database.schema;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.q3lives.ds.annotation.DsCompositeField;
import com.q3lives.ds.annotation.DsCompositeIndex;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;

public final class EntitySchemaUtil {
    private EntitySchemaUtil() {
    }

    public static SchemaDef schemaOf(Class<? extends DsTableAdapter> clazz) {
        List<Field> allFields = getAllFields(clazz);

        List<CompositeDef> composites = new ArrayList<>();
        List<ColumnDef> columns = new ArrayList<>();

        for (Field f : allFields) {
            if (f.isAnnotationPresent(DsCompositeField.class)) {
                DsCompositeField a = f.getAnnotation(DsCompositeField.class);
                String name = a.name().isEmpty() ? f.getName() : a.name();
                composites.add(new CompositeDef(name, a.group(), a.length(), a.startBits(), a.endBits(), f));
            }
            if (f.isAnnotationPresent(DsField.class)) {
                DsField a = f.getAnnotation(DsField.class);
                String name = a.name().isEmpty() ? f.getName() : a.name();
                String defVal = a.defaultValue();
                columns.add(new ColumnDef(name, f.getType().getName(), a.length(), a.precision(), a.scale(), defVal, a.indexed(), f));
            }
        }

        composites.sort(Comparator
            .comparing((CompositeDef d) -> d.group)
            .thenComparingInt(d -> d.startBits));

        columns.sort(Comparator.comparingInt(d -> getFieldOrder(allFields, d.declaredField)));

        int rowLength = 8;
        for (CompositeDef d : composites) {
            rowLength += d.length;
        }
        for (ColumnDef d : columns) {
            rowLength += d.length;
        }

        List<CompositeIndexDef> compositeIndexes = parseCompositeIndexes(clazz, columns);

        String signature = signature(rowLength, composites, columns, compositeIndexes);
        String schemaId = shortSha256Hex(signature);
        return new SchemaDef(schemaId, rowLength, columns, composites, compositeIndexes);
    }

    private static List<CompositeIndexDef> parseCompositeIndexes(Class<? extends DsTableAdapter> clazz, List<ColumnDef> columns) {
        List<CompositeIndexDef> out = new ArrayList<>();
        DsCompositeIndex.List list = clazz.getAnnotation(DsCompositeIndex.List.class);
        DsCompositeIndex single = clazz.getAnnotation(DsCompositeIndex.class);
        if (list != null) {
            for (DsCompositeIndex ci : list.value()) {
                out.add(buildCompositeIndexDef(ci, columns));
            }
        } else if (single != null) {
            out.add(buildCompositeIndexDef(single, columns));
        }
        return out;
    }

    private static CompositeIndexDef buildCompositeIndexDef(DsCompositeIndex ci, List<ColumnDef> columns) {
        String[] names = ci.columns();
        if (names == null || names.length == 0) {
            throw new IllegalArgumentException("DsCompositeIndex columns cannot be empty: " + ci.name());
        }
        List<ColumnDef> resolved = new ArrayList<>(names.length);
        for (String n : names) {
            ColumnDef match = null;
            for (ColumnDef d : columns) {
                if (d.name.equals(n)) {
                    match = d;
                    break;
                }
            }
            if (match == null) {
                throw new IllegalArgumentException("DsCompositeIndex column not found: " + n + " in index " + ci.name());
            }
            resolved.add(match);
        }
        return new CompositeIndexDef(ci.name(), Collections.unmodifiableList(resolved));
    }

    private static String signature(int rowLength, List<CompositeDef> composites, List<ColumnDef> columns,
                                    List<CompositeIndexDef> compositeIndexes) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("v1|rowLength=").append(rowLength).append('\n');
        for (CompositeDef d : composites) {
            sb.append("C|")
                .append(d.name).append('|')
                .append(d.group).append('|')
                .append(d.length).append('|')
                .append(d.startBits).append('|')
                .append(d.endBits).append('\n');
        }
        for (ColumnDef d : columns) {
            sb.append("F|")
                .append(d.name).append('|')
                .append(d.typeName).append('|')
                .append(d.length).append('|')
                .append(d.precision).append('|')
                .append(d.scale)
                .append("|I=").append(d.indexed ? 1 : 0).append('\n');
        }
        for (CompositeIndexDef d : compositeIndexes) {
            sb.append("I|").append(d.name);
            for (ColumnDef c : d.columns) {
                sb.append('|').append(c.name);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String shortSha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                int v = digest[i] & 0xFF;
                out.append(Character.forDigit(v >>> 4, 16));
                out.append(Character.forDigit(v & 0x0F, 16));
            }
            return out.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
            c = c.getSuperclass();
        }
        return fields;
    }

    private static int getFieldOrder(List<Field> allFields, Field field) {
        for (int i = 0; i < allFields.size(); i++) {
            if (allFields.get(i).equals(field)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    public static final class SchemaDef {
        public final String schemaId;
        public final int rowLength;
        private final List<ColumnDef> columns;
        private final List<CompositeDef> composites;
        private final List<CompositeIndexDef> compositeIndexes;

        public SchemaDef(String schemaId, int rowLength, List<ColumnDef> columns, List<CompositeDef> composites,
                         List<CompositeIndexDef> compositeIndexes) {
            this.schemaId = schemaId;
            this.rowLength = rowLength;
            this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
            this.composites = Collections.unmodifiableList(new ArrayList<>(composites));
            this.compositeIndexes = Collections.unmodifiableList(new ArrayList<>(compositeIndexes));
        }

        public List<ColumnDef> getColumns() {
            return columns;
        }

        public List<CompositeDef> getComposites() {
            return composites;
        }

        public List<CompositeIndexDef> getCompositeIndexes() {
            return compositeIndexes;
        }
    }

    public static final class CompositeDef {
        public final String name;
        public final String group;
        public final int length;
        public final int startBits;
        public final int endBits;
        public final Field declaredField;

        CompositeDef(String name, String group, int length, int startBits, int endBits, Field declaredField) {
            this.name = name;
            this.group = group;
            this.length = length;
            this.startBits = startBits;
            this.endBits = endBits;
            this.declaredField = declaredField;
        }
    }

    public static final class CompositeIndexDef {
        public final String name;
        public final List<ColumnDef> columns;

        public CompositeIndexDef(String name, List<ColumnDef> columns) {
            this.name = name;
            this.columns = columns;
        }

        /**
         * 检查查询条件列名列表是否匹配该复合索引的最左前缀。
         */
        public boolean matchesLeftPrefix(List<String> queryCols) {
            if (queryCols == null || queryCols.isEmpty()) return false;
            if (queryCols.size() > columns.size()) return false;
            for (int i = 0; i < queryCols.size(); i++) {
                if (!columns.get(i).name.equals(queryCols.get(i))) return false;
            }
            return true;
        }
    }

    public static final class ColumnDef {
        public final String name;
        public final String typeName;
        public final int length;
        public final int precision;
        public final int scale;
        public final String defaultValue;
        public final boolean indexed;
        public final Field declaredField;

        ColumnDef(String name, String typeName, int length, int precision, int scale, String defaultValue, boolean indexed, Field declaredField) {
            this.name = name;
            this.typeName = typeName;
            this.length = length;
            this.precision = precision;
            this.scale = scale;
            this.defaultValue = defaultValue;
            this.indexed = indexed;
            this.declaredField = declaredField;
        }
    }
}
