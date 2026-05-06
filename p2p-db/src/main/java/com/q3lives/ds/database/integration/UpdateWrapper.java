/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.q3lives.ds.database.integration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 *
 * @author Administrator
 */
public class UpdateWrapper {
    
    private final QueryWrapper<?> where;
    private final Map<String, Object> sets = new LinkedHashMap<>();
    
    public UpdateWrapper(QueryWrapper<?> where) {
        this.where = where;
    }
    
    public QueryWrapper<?> where() {
        return where;
    }
    
    public Map<String, Object> sets() {
        return sets;
    }
    
    public UpdateWrapper set(String col, Object value) {
        if (col != null && !col.isBlank()) {
            sets.put(col.trim(), value);
        }
        return this;
    }
}
