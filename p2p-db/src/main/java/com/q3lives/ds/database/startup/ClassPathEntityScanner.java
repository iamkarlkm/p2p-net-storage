package com.q3lives.ds.database.startup;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class ClassPathEntityScanner {
    private ClassPathEntityScanner() {
    }

    static List<Class<? extends DsTableAdapter>> scanPackages(List<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return List.of();
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ClassPathEntityScanner.class.getClassLoader();
        }
        HashSet<String> classNames = new HashSet<>();
        for (String pkg : packages) {
            if (pkg == null || pkg.isBlank()) {
                continue;
            }
            String path = pkg.replace('.', '/');
            try {
                Enumeration<URL> urls = cl.getResources(path);
                while (urls.hasMoreElements()) {
                    URL url = urls.nextElement();
                    if (url == null) {
                        continue;
                    }
                    String protocol = url.getProtocol();
                    if ("file".equals(protocol)) {
                        scanDir(classNames, pkg, new File(URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8)));
                        continue;
                    }
                    if ("jar".equals(protocol)) {
                        scanJar(classNames, url, path);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("scan package failed: " + pkg + ", " + e.getMessage(), e);
            }
        }
        ArrayList<Class<? extends DsTableAdapter>> out = new ArrayList<>();
        for (String cn : classNames) {
            Class<?> raw;
            try {
                raw = Class.forName(cn, false, cl);
            } catch (Throwable ignored) {
                continue;
            }
            if (!DsTableAdapter.class.isAssignableFrom(raw) || raw == DsTableAdapter.class) {
                continue;
            }
            if (raw.isInterface() || java.lang.reflect.Modifier.isAbstract(raw.getModifiers())) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends DsTableAdapter> c = (Class<? extends DsTableAdapter>) raw;
            out.add(c);
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static void scanDir(Set<String> classNames, String pkg, File root) {
        if (root == null || !root.exists() || !root.isDirectory()) {
            return;
        }
        ArrayDeque<File> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            File dir = q.removeFirst();
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (f == null) {
                    continue;
                }
                if (f.isDirectory()) {
                    q.add(f);
                    continue;
                }
                String name = f.getName();
                if (!name.endsWith(".class") || name.contains("$")) {
                    continue;
                }
                String rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
                String cn = pkg + "." + rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
                classNames.add(cn);
            }
        }
    }

    private static void scanJar(Set<String> classNames, URL url, String pkgPath) throws IOException {
        JarURLConnection conn = (JarURLConnection) url.openConnection();
        try (JarFile jar = conn.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e == null) {
                    continue;
                }
                String name = e.getName();
                if (name == null || !name.startsWith(pkgPath) || !name.endsWith(".class") || name.contains("$")) {
                    continue;
                }
                String cn = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                classNames.add(cn);
            }
        }
    }
}

