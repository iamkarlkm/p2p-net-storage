package com.q3lives.ds.validator;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.annotation.DsField;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 实体验证器
 * 
 * 验证实体字段的有效性
 */
public class EntityValidator {
    
    /**
     * 验证实体
     */
    public static ValidationResult validate(DsTableAdapter entity) {
        ValidationResult result = new ValidationResult();
        
        try {
            Class<?> clazz = entity.getClass();
            
            while (clazz != null && clazz != Object.class) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (field.isAnnotationPresent(DsField.class)) {
                        validateField(entity, field, result);
                    }
                }
                clazz = clazz.getSuperclass();
            }
            
        } catch (Exception e) {
            result.addError("验证过程出错: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 验证单个字段
     */
    private static void validateField(
            DsTableAdapter entity, 
            Field field, 
            ValidationResult result) throws Exception {
        
        field.setAccessible(true);
        Object value = field.get(entity);
        DsField annotation = field.getAnnotation(DsField.class);
        
        String fieldName = annotation.name().isEmpty() ? field.getName() : annotation.name();
        
        // 检查必填字段
        if (annotation.nullable() == false && value == null) {
            result.addError(fieldName + " 不能为空");
        }
        
        // 检查字符串长度
        if (value instanceof String) {
            String strValue = (String) value;
            int maxLength = annotation.length();
            
            if (strValue.getBytes().length > maxLength) {
                result.addError(fieldName + " 超过最大长度 " + maxLength);
            }
        }
        
        // 检查数值范围
        if (value instanceof Number) {
            Number numValue = (Number) value;
            
            if (annotation.min() != Long.MIN_VALUE && 
                numValue.longValue() < annotation.min()) {
                result.addError(fieldName + " 小于最小值 " + annotation.min());
            }
            
            if (annotation.max() != Long.MAX_VALUE && 
                numValue.longValue() > annotation.max()) {
                result.addError(fieldName + " 大于最大值 " + annotation.max());
            }
        }
    }
    
    /**
     * 验证结果
     */
    public static class ValidationResult {
        private final List<String> errors = new ArrayList<>();
        
        public void addError(String error) {
            errors.add(error);
        }
        
        public boolean isValid() {
            return errors.isEmpty();
        }
        
        public List<String> getErrors() {
            return new ArrayList<>(errors);
        }
        
        @Override
        public String toString() {
            if (isValid()) {
                return "验证通过";
            }
            return "验证失败:\n  " + String.join("  ", errors);
        }
    }
}
