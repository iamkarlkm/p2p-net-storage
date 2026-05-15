package com.q3lives.ds.database.startup;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsManyToMany;
import com.q3lives.ds.annotation.DsMapField;
import com.q3lives.ds.annotation.DsOneToMany;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.p2p.startup.P2PStartupCheck;

public final class DsDbRelationStartupCheck implements P2PStartupCheck {
    @Override
    public void check() {
        DsDbStartupCheckConfig cfg = DsDbStartupCheckConfig.load();
        if (!cfg.enabled) {
            return;
        }
        ArrayList<Class<? extends DsTableAdapter>> entities = new ArrayList<>();
        for (String cn : cfg.entityClasses) {
            entities.add(loadEntityClass(cn));
        }
        entities.addAll(ClassPathEntityScanner.scanPackages(cfg.entityPackages));
        if (entities.isEmpty()) {
            if (cfg.strict) {
                throw new IllegalStateException("DsDbStartupCheck enabled but no entityClasses/entityPackages configured");
            }
            return;
        }
        ArrayList<String> issues = new ArrayList<>();
        for (Class<? extends DsTableAdapter> c : entities) {
            validateEntity(c, cfg, issues);
        }
        if (!issues.isEmpty()) {
            int limit = Math.min(30, issues.size());
            throw new IllegalStateException("DsDbStartupCheck failed (" + issues.size() + "): " + issues.subList(0, limit));
        }
    }

    private static Class<? extends DsTableAdapter> loadEntityClass(String cn) {
        if (cn == null || cn.isBlank()) {
            throw new IllegalArgumentException("blank entity class name");
        }
        try {
            Class<?> raw = Class.forName(cn);
            if (!DsTableAdapter.class.isAssignableFrom(raw)) {
                throw new IllegalArgumentException("not DsTableAdapter: " + cn);
            }
            if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) {
                throw new IllegalArgumentException("entity class is abstract/interface: " + cn);
            }
            @SuppressWarnings("unchecked")
            Class<? extends DsTableAdapter> c = (Class<? extends DsTableAdapter>) raw;
            return c;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("load entity failed: " + cn + ", " + e.getMessage(), e);
        }
    }

    private static void validateEntity(Class<? extends DsTableAdapter> c, DsDbStartupCheckConfig cfg, List<String> issues) {
        try {
            c.getDeclaredConstructor();
        } catch (Exception e) {
            issues.add(c.getName() + ": missing no-arg constructor");
        }
        if (cfg.failOnSuperclassRelationFields) {
            Class<?> p = c.getSuperclass();
            while (p != null && p != Object.class) {
                for (Field f : p.getDeclaredFields()) {
                    if (isRelationField(f)) {
                        issues.add(c.getName() + ": relation field in superclass will be ignored by codec: " + p.getName() + "#" + f.getName());
                    }
                }
                p = p.getSuperclass();
            }
        }

        for (Field f : c.getDeclaredFields()) {
            int relCount = relationAnnotationCount(f);
            if (relCount == 0) {
                continue;
            }
            if (relCount > 1) {
                issues.add(c.getName() + "#" + f.getName() + ": multiple relation annotations");
                continue;
            }
            if (f.isAnnotationPresent(DsField.class)) {
                issues.add(c.getName() + "#" + f.getName() + ": DsField conflicts with relation annotation");
            }
            if (Modifier.isStatic(f.getModifiers())) {
                issues.add(c.getName() + "#" + f.getName() + ": relation field must not be static");
            }
            if (Modifier.isFinal(f.getModifiers())) {
                issues.add(c.getName() + "#" + f.getName() + ": relation field must not be final (codec apply sets value)");
            }
            validateTypeCompatibility(c, f, cfg, issues);
        }
    }

    private static boolean isRelationField(Field f) {
        return f.isAnnotationPresent(DsOneToOne.class)
            || f.isAnnotationPresent(DsOneToMany.class)
            || f.isAnnotationPresent(DsManyToMany.class)
            || f.isAnnotationPresent(DsMapField.class);
    }

    private static int relationAnnotationCount(Field f) {
        int n = 0;
        if (f.isAnnotationPresent(DsOneToOne.class)) {
            n++;
        }
        if (f.isAnnotationPresent(DsOneToMany.class)) {
            n++;
        }
        if (f.isAnnotationPresent(DsManyToMany.class)) {
            n++;
        }
        if (f.isAnnotationPresent(DsMapField.class)) {
            n++;
        }
        return n;
    }

    private static void validateTypeCompatibility(Class<?> owner, Field f, DsDbStartupCheckConfig cfg, List<String> issues) {
        Class<?> t = f.getType();
        String fn = owner.getName() + "#" + f.getName();

        if (f.isAnnotationPresent(DsOneToOne.class)) {
            if (!DsTableAdapter.class.isAssignableFrom(t)) {
                issues.add(fn + ": DsOneToOne field type must be DsTableAdapter");
            }
            DsOneToOne a = f.getAnnotation(DsOneToOne.class);
            if (a.joinProp() == null || a.joinProp().isBlank()) {
                issues.add(fn + ": DsOneToOne.joinProp is blank");
            }
            return;
        }
        if (f.isAnnotationPresent(DsOneToMany.class) || f.isAnnotationPresent(DsManyToMany.class)) {
            if (!Collection.class.isAssignableFrom(t)) {
                issues.add(fn + ": relation field must be Collection");
            } else if (!t.isAssignableFrom(ArrayList.class)) {
                issues.add(fn + ": relation collection field type must accept ArrayList (use List/Collection)");
            }
            if (f.isAnnotationPresent(DsOneToMany.class)) {
                DsOneToMany a = f.getAnnotation(DsOneToMany.class);
                if (a.joinClass() == null) {
                    issues.add(fn + ": DsOneToMany.joinClass is null");
                }
                if (a.joinProp() == null || a.joinProp().isBlank()) {
                    issues.add(fn + ": DsOneToMany.joinProp is blank");
                }
            }
            validateGenericArgs(fn, f, cfg, issues, 1);
            return;
        }
        if (f.isAnnotationPresent(DsMapField.class)) {
            if (!t.isAssignableFrom(LinkedHashMap.class)) {
                issues.add(fn + ": DsMapField type must accept LinkedHashMap (use Map)");
            }
            validateGenericArgs(fn, f, cfg, issues, 2);
        }
    }

    private static void validateGenericArgs(String fn, Field f, DsDbStartupCheckConfig cfg, List<String> issues, int expected) {
        Type gt = f.getGenericType();
        if (!(gt instanceof ParameterizedType pt)) {
            if (cfg.strict) {
                issues.add(fn + ": missing generic type parameters");
            }
            return;
        }
        Type[] args = pt.getActualTypeArguments();
        if (args == null || args.length != expected) {
            if (cfg.strict) {
                issues.add(fn + ": generic parameter count mismatch");
            }
            return;
        }
        for (Type a : args) {
            if (a instanceof Class<?> ac) {
                if (!DsTableAdapter.class.isAssignableFrom(ac)) {
                    issues.add(fn + ": generic type must be DsTableAdapter");
                }
                continue;
            }
            if (cfg.strict) {
                issues.add(fn + ": unsupported generic type");
            }
        }
    }
}
