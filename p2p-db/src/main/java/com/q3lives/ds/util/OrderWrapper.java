/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.q3lives.ds.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 * @author Administrator
 */
public class OrderWrapper {

    public static final class Item {
        public final String prop;
        public final boolean asc;

        public Item(String prop, boolean asc) {
            this.prop = prop;
            this.asc = asc;
        }
    }

    private final List<Item> items = new ArrayList<>();

    public static OrderWrapper build() {
        return new OrderWrapper();
    }

    public OrderWrapper orderAsc(String value) {
        if (value != null && !value.isBlank()) {
            items.add(new Item(value.trim(), true));
        }
        return this;
    }

    public OrderWrapper orderDesc(String value) {
        if (value != null && !value.isBlank()) {
            items.add(new Item(value.trim(), false));
        }
        return this;
    }

    public List<Item> items() {
        return Collections.unmodifiableList(items);
    }
    
}
