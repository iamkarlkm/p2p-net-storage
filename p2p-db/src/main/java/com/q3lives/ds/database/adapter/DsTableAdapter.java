package com.q3lives.ds.database.adapter;

import com.q3lives.ds.annotation.DsCompositeField;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.interfaces.DsTableByteBufferSerializable;
import com.q3lives.ds.exception.SerializationException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据库ORM自动存储(序列化)和装载(反序列化)适配器基类
 * 
 * 功能特性:
 * - 自动扫描字段注解建立ORM映射
 * - 支持行存储和复合列存储
 * - 支持基本类型、String、Date、BigDecimal等
 * - 字节对齐优化
 * - 高性能序列化/反序列化
 * 
 * @author Q3Lives Team
 * @version 1.0
 */
public abstract class DsTableAdapter implements DsTableByteBufferSerializable {
    
    // 映射缓存，避免重复反射
    private static final Map<Class<?>, FieldMapping> MAPPING_CACHE = new ConcurrentHashMap<>();
    
    // 实体ID
    private Long id;
    
    // 字段映射信息
    private FieldMapping fieldMapping;
    
    /**
     * 构造函数 - 初始化时扫描注解
     */
    public DsTableAdapter() {
        this.fieldMapping = getOrCreateMapping(this.getClass());
    }
    
    @Override
    public Long getId() {
        return id;
    }
    
    @Override
    public void setId(long id) {
        this.id = id;
    }
    
    /**
     * 序列化为ByteBuffer
     */
    @Override
    public ByteBuffer toBytes() {
        try {
            // 计算总大小
            int totalSize = calculateTotalSize();
            ByteBuffer buffer = ByteBuffer.allocate(totalSize);
            
            // 写入ID (8字节)
            buffer.putLong(id != null ? id : 0L);
            
            // 按顺序写入复合列存储字段
            for (CompositeFieldInfo compositeInfo : fieldMapping.compositeFields) {
                writeCompositeField(buffer, compositeInfo);
            }
            
            // 按顺序写入普通列存储字段
            for (ColumnFieldInfo columnInfo : fieldMapping.columnFields) {
                writeColumnField(buffer, columnInfo);
            }
            
            buffer.flip();
            return buffer;
            
        } catch (Exception e) {
            throw new SerializationException("序列化失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 从ByteBuffer反序列化
     */
    @Override
    public void load(ByteBuffer data) {
        try {
            data.rewind();
            
            // 读取ID
            this.id = data.getLong();
            
            // 按顺序读取复合列存储字段
            for (CompositeFieldInfo compositeInfo : fieldMapping.compositeFields) {
                readCompositeField(data, compositeInfo);
            }
            
            // 按顺序读取普通列存储字段
            for (ColumnFieldInfo columnInfo : fieldMapping.columnFields) {
                readColumnField(data, columnInfo);
            }
            
        } catch (Exception e) {
            throw new SerializationException("反序列化失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 计算总大小
     */
    private int calculateTotalSize() {
        int size = 8; // ID占8字节
        
        // 复合列存储字段大小
        for (CompositeFieldInfo info : fieldMapping.compositeFields) {
            size += info.length;
        }
        
        // 普通列存储字段大小
        for (ColumnFieldInfo info : fieldMapping.columnFields) {
            size += info.length;
        }
        
        return size;
    }
    
    /**
     * 写入复合列存储字段
     */
    private void writeCompositeField(ByteBuffer buffer, CompositeFieldInfo info) throws Exception {
        Field field = info.field;
        field.setAccessible(true);
        Object value = field.get(this);
        
        // 创建临时缓冲区用于位操作
        byte[] compositeBytes = new byte[info.length];
        
        // 根据位范围写入数据
        int bitLength = info.endBits - info.startBits + 1;
        long longValue = convertToLong(value, field.getType());
        
        // 将值写入指定的位范围
        writeBits(compositeBytes, info.startBits, bitLength, longValue);
        
        buffer.put(compositeBytes);
    }
    
    /**
     * 读取复合列存储字段
     */
    private void readCompositeField(ByteBuffer buffer, CompositeFieldInfo info) throws Exception {
        byte[] compositeBytes = new byte[info.length];
        buffer.get(compositeBytes);
        
        // 从指定位范围读取数据
        int bitLength = info.endBits - info.startBits + 1;
        long longValue = readBits(compositeBytes, info.startBits, bitLength);
        
        // 转换为目标类型并设置
        Field field = info.field;
        field.setAccessible(true);
        Object value = convertFromLong(longValue, field.getType());
        field.set(this, value);
    }
    
    /**
     * 写入普通列存储字段
     */
    private void writeColumnField(ByteBuffer buffer, ColumnFieldInfo info) throws Exception {
        Field field = info.field;
        field.setAccessible(true);
        Object value = field.get(this);
        
        Class<?> type = field.getType();
        
        if (type == byte.class || type == Byte.class) {
            buffer.put(value != null ? (Byte) value : 0);
            
        } else if (type == short.class || type == Short.class) {
            buffer.putShort(value != null ? (Short) value : 0);
            
        } else if (type == int.class || type == Integer.class) {
            buffer.putInt(value != null ? (Integer) value : 0);
            
        } else if (type == long.class || type == Long.class) {
            buffer.putLong(value != null ? (Long) value : 0L);
            
        } else if (type == float.class || type == Float.class) {
            buffer.putFloat(value != null ? (Float) value : 0.0f);
            
        } else if (type == double.class || type == Double.class) {
            buffer.putDouble(value != null ? (Double) value : 0.0);
            
        } else if (type == boolean.class || type == Boolean.class) {
            buffer.put((byte) (value != null && (Boolean) value ? 1 : 0));
            
        } else if (type == char.class || type == Character.class) {
            buffer.putChar(value != null ? (Character) value : '\0');
            
        } else if (type == String.class) {
            writeString(buffer, (String) value, info.length);
            
        } else if (type == Date.class) {
            long timestamp = value != null ? ((Date) value).getTime() : 0L;
            buffer.putLong(timestamp);
            
        } else if (type == BigDecimal.class) {
            writeBigDecimal(buffer, (BigDecimal) value, info.precision, info.scale);
            
        } else {
            throw new SerializationException("不支持的字段类型: " + type.getName());
        }
    }
    
    /**
     * 读取普通列存储字段
     */
    private void readColumnField(ByteBuffer buffer, ColumnFieldInfo info) throws Exception {
        Field field = info.field;
        field.setAccessible(true);
        Class<?> type = field.getType();
        
        Object value;
        
        if (type == byte.class || type == Byte.class) {
            value = buffer.get();
            
        } else if (type == short.class || type == Short.class) {
            value = buffer.getShort();
            
        } else if (type == int.class || type == Integer.class) {
            value = buffer.getInt();
            
        } else if (type == long.class || type == Long.class) {
            value = buffer.getLong();
            
        } else if (type == float.class || type == Float.class) {
            value = buffer.getFloat();
            
        } else if (type == double.class || type == Double.class) {
            value = buffer.getDouble();
            
        } else if (type == boolean.class || type == Boolean.class) {
            value = buffer.get() != 0;
            
        } else if (type == char.class || type == Character.class) {
            value = buffer.getChar();
            
        } else if (type == String.class) {
            value = readString(buffer, info.length);
            
        } else if (type == Date.class) {
            long timestamp = buffer.getLong();
            value = timestamp != 0 ? new Date(timestamp) : null;
            
        } else if (type == BigDecimal.class) {
            value = readBigDecimal(buffer, info.precision, info.scale);
            
        } else {
            throw new SerializationException("不支持的字段类型: " + type.getName());
        }
        
        field.set(this, value);
    }
    
    /**
     * 写入字符串（定长，UTF-8编码）
     */
    private void writeString(ByteBuffer buffer, String value, int length) {
        byte[] bytes = new byte[length];
        
        if (value != null && !value.isEmpty()) {
            byte[] strBytes = value.getBytes(StandardCharsets.UTF_8);
            int copyLen = Math.min(strBytes.length, length);
            System.arraycopy(strBytes, 0, bytes, 0, copyLen);
        }
        
        buffer.put(bytes);
    }
    
    /**
     * 读取字符串
     */
    private String readString(ByteBuffer buffer, int length) {
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        
        // 找到第一个0字节（字符串结束符）
        int actualLength = 0;
        for (int i = 0; i < length; i++) {
            if (bytes[i] == 0) {
                break;
            }
            actualLength++;
        }
        
        if (actualLength == 0) {
            return null;
        }
        
        return new String(bytes, 0, actualLength, StandardCharsets.UTF_8);
    }
    
    /**
     * 写入BigDecimal（使用定点数表示）
     */
    private void writeBigDecimal(ByteBuffer buffer, BigDecimal value, int precision, int scale) {
        if (value == null) {
            buffer.putLong(0);
            return;
        }
        
        // 转换为定点数: value * 10^scale
        BigDecimal scaled = value.setScale(scale, BigDecimal.ROUND_HALF_UP);
        long unscaledValue = scaled.unscaledValue().longValue();
        buffer.putLong(unscaledValue);
    }
    
    /**
     * 读取BigDecimal
     */
    private BigDecimal readBigDecimal(ByteBuffer buffer, int precision, int scale) {
        long unscaledValue = buffer.getLong();
        
        if (unscaledValue == 0) {
            return null;
        }
        
        return new BigDecimal(unscaledValue).movePointLeft(scale);
    }
    
    /**
     * 将值转换为long（用于位操作）
     */
    private long convertToLong(Object value, Class<?> type) {
        if (value == null) {
            return 0L;
        }
        
        if (type == byte.class || type == Byte.class) {
            return ((Byte) value).longValue();
        } else if (type == short.class || type == Short.class) {
            return ((Short) value).longValue();
        } else if (type == int.class || type == Integer.class) {
            return ((Integer) value).longValue();
        } else if (type == long.class || type == Long.class) {
            return (Long) value;
        } else if (type == boolean.class || type == Boolean.class) {
            return (Boolean) value ? 1L : 0L;
        }
        
        throw new SerializationException("无法转换类型到long: " + type.getName());
    }
    
    /**
     * 从long转换为目标类型
     */
    private Object convertFromLong(long value, Class<?> type) {
        if (type == byte.class || type == Byte.class) {
            return (byte) value;
        } else if (type == short.class || type == Short.class) {
            return (short) value;
        } else if (type == int.class || type == Integer.class) {
            return (int) value;
        } else if (type == long.class || type == Long.class) {
            return value;
        } else if (type == boolean.class || type == Boolean.class) {
            return value != 0;
        }
        
        throw new SerializationException("无法从long转换到类型: " + type.getName());
    }
    
    /**
     * 写入位数据
     */
    private void writeBits(byte[] bytes, int startBit, int bitLength, long value) {
        for (int i = 0; i < bitLength; i++) {
            int bitPos = startBit + i;
            int byteIndex = bitPos / 8;
            int bitIndex = bitPos % 8;
            
            if ((value & (1L << i)) != 0) {
                bytes[byteIndex] |= (1 << bitIndex);
            }
        }
    }
    
    /**
     * 读取位数据
     */
    private long readBits(byte[] bytes, int startBit, int bitLength) {
        long value = 0;
        
        for (int i = 0; i < bitLength; i++) {
            int bitPos = startBit + i;
            int byteIndex = bitPos / 8;
            int bitIndex = bitPos % 8;
            
            if ((bytes[byteIndex] & (1 << bitIndex)) != 0) {
                value |= (1L << i);
            }
        }
        
        return value;
    }
    
    /**
     * 获取或创建字段映射
     */
    private static FieldMapping getOrCreateMapping(Class<?> clazz) {
        return MAPPING_CACHE.computeIfAbsent(clazz, DsTableAdapter::createMapping);
    }
    
    /**
     * 创建字段映射
     */
    private static FieldMapping createMapping(Class<?> clazz) {
        FieldMapping mapping = new FieldMapping();
        
        // 扫描所有字段
        List<Field> allFields = getAllFields(clazz);
        
        for (Field field : allFields) {
            // 处理复合列存储字段
            if (field.isAnnotationPresent(DsCompositeField.class)) {
                DsCompositeField annotation = field.getAnnotation(DsCompositeField.class);
                CompositeFieldInfo info = new CompositeFieldInfo();
                info.field = field;
                info.name = annotation.name().isEmpty() ? field.getName() : annotation.name();
                info.group = annotation.group();
                info.length = annotation.length();
                info.startBits = annotation.startBits();
                info.endBits = annotation.endBits();
                mapping.compositeFields.add(info);
            }
            
            // 处理普通列存储字段
            if (field.isAnnotationPresent(DsField.class)) {
                DsField annotation = field.getAnnotation(DsField.class);
                ColumnFieldInfo info = new ColumnFieldInfo();
                info.field = field;
                info.name = annotation.name().isEmpty() ? field.getName() : annotation.name();
                info.length = annotation.length();
                info.precision = annotation.precision();
                info.scale = annotation.scale();
                mapping.columnFields.add(info);
            }
        }
        
        // 按组和位置排序复合字段
        mapping.compositeFields.sort(Comparator
            .comparing((CompositeFieldInfo f) -> f.group)
            .thenComparing(f -> f.startBits));
        
        // 按字段声明顺序排序普通字段
        mapping.columnFields.sort(Comparator.comparing(f -> getFieldOrder(allFields, f.field)));
        
        return mapping;
    }
    
    /**
     * 获取字段顺序
     */
    private static int getFieldOrder(List<Field> allFields, Field field) {
        for (int i = 0; i < allFields.size(); i++) {
            if (allFields.get(i).equals(field)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }
    
    /**
     * 获取所有字段（包括父类）
     */
    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        
        return fields;
    }
    
    /**
     * 字段映射信息
     */
    private static class FieldMapping {
        List<CompositeFieldInfo> compositeFields = new ArrayList<>();
        List<ColumnFieldInfo> columnFields = new ArrayList<>();
    }
    
    /**
     * 复合列存储字段信息
     */
    private static class CompositeFieldInfo {
        Field field;
        String name;
        String group;
        int length;
        int startBits;
        int endBits;
    }
    
    /**
     * 普通列存储字段信息
     */
    private static class ColumnFieldInfo {
        Field field;
        String name;
        int length;
        int precision;
        int scale;
    }
    
    /**
     * 获取字段映射信息（用于调试）
     */
    public String getMappingInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 字段映射信息 ===");
        sb.append("类: ").append(this.getClass().getName()).append("\n");
        
        sb.append("复合列存储字段:");
        for (CompositeFieldInfo info : fieldMapping.compositeFields) {
            sb.append(String.format("  %s [%s] - 长度:%d, 位范围:%d-%d",
                info.name, info.group, info.length, info.startBits, info.endBits));
        }
        
        sb.append("普通列存储字段:");
        for (ColumnFieldInfo info : fieldMapping.columnFields) {
            sb.append(String.format("  %s - 长度:%d, 精度:%d, 小数位:%d",
                info.name, info.length, info.precision, info.scale));
        }
        
        sb.append("总大小: ").append(calculateTotalSize()).append(" 字节");
        
        return sb.toString();
    }
}
