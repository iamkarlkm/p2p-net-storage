
package com.q3lives.ds.database.orm;

import com.q3lives.ds.validator.EntityValidator.ValidationResult;

public class CustomValidator {
    
    public static ValidationResult validateEmail(String email) {
        ValidationResult result = new ValidationResult();
        
        if (!email.matches("^[A-Za-z0-9+_.-]+@(.+)$")) {
            result.addError("邮箱格式不正确");
        }
        
        return result;
    }
}
