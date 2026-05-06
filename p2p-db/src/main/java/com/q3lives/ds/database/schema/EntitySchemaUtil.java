package com.q3lives.ds.database.schema;

import com.q3lives.ds.annotation.DsCompositeField;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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
                composites.add(new CompositeDef(name, a.group(), a.length(), a.startBits(), a.endBits()));
            }
            if (f.isAnnotationPresent(DsField.class)) {
                DsField a = f.getAnnotation(DsField.class);
                String name = a.name().isEmpty() ? f.getName() : a.name();
                columns.add(new ColumnDef(name, f.getType().getName(), a.length(), a.precision(), a.scale(), f));
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

        String signature = signature(rowLength, composites, columns);
        String schemaId = shortSha256Hex(signature);
        return new SchemaDef(schemaId, rowLength);
    }

    private static String signature(int rowLength, List<CompositeDef> composites, List<ColumnDef> columns) {
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
                .append(d.scale).append('\n');
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

        public SchemaDef(String schemaId, int rowLength) {
            this.schemaId = schemaId;
            this.rowLength = rowLength;
        }
    }

    private static final class CompositeDef {
        final String name;
        final String group;
        final int length;
        final int startBits;
        final int endBits;

        CompositeDef(String name, String group, int length, int startBits, int endBits) {
            this.name = name;
            this.group = group;
            this.length = length;
            this.startBits = startBits;
            this.endBits = endBits;
        }
    }

    private static final class ColumnDef {
        final String name;
        final String typeName;
        final int length;
        final int precision;
        final int scale;
        final Field declaredField;

        ColumnDef(String name, String typeName, int length, int precision, int scale, Field declaredField) {
            this.name = name;
            this.typeName = typeName;
            this.length = length;
            this.precision = precision;
            this.scale = scale;
            this.declaredField = declaredField;
        }
    }
}
