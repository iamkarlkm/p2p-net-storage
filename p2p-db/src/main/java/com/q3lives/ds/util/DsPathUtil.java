package com.q3lives.ds.util;

/**
 * 路径与名称校验工具（Linux 风格）。
 *
 * <p>目标：</p>
 * <ul>
 *   <li>统一使用 '/' 作为路径分隔符（禁止 '\\'）。</li>
 *   <li>禁止目录穿透：任何 "." / ".." 段一律抛异常。</li>
 *   <li>相对路径必须结合父路径解析（{@link #resolveLinuxPath(String, String)}）。</li>
 *   <li>space/type/storeName 等“单段名称”必须通过 {@link #validateSegment(String, String)} 校验。</li>
 * </ul>
 */
public final class DsPathUtil {

    private DsPathUtil() {
    }

    /**
     * 规范化 Linux 风格路径。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>只允许 '/' 作为分隔符；禁止 '\\'。</li>
     *   <li>禁止空段（例如连续的 '//' 或尾部 '/'）与非法段（'.'/'..'）。</li>
     *   <li>requireAbsolute=true 时必须以 '/' 开头。</li>
     * </ul>
     *
     * @param path 原始路径
     * @param requireAbsolute 是否要求绝对路径
     * @return 规范化后的路径
     */
    public static String normalizeLinuxPath(String path, boolean requireAbsolute) {
        if (path == null) {
            throw new IllegalArgumentException("path cannot be null");
        }
        if (path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("path must use '/'");
        }
        boolean absolute = path.startsWith("/");
        if (requireAbsolute && !absolute) {
            throw new IllegalArgumentException("path must be absolute and start with '/'");
        }
        String[] raw = path.split("/", -1);
        StringBuilder out = new StringBuilder(path.length() + 2);
        if (absolute) {
            out.append('/');
        }
        boolean first = true;
        for (int i = 0; i < raw.length; i++) {
            String seg = raw[i];
            if (seg.isEmpty()) {
                if (i == 0 && absolute) {
                    continue;
                }
                if (i == raw.length - 1) {
                    continue;
                }
                throw new IllegalArgumentException("path contains empty segment");
            }
            validateSegment(seg, "path segment");
            if (!first) {
                out.append('/');
            }
            out.append(seg);
            first = false;
        }
        if (out.length() == 0) {
            return absolute ? "/" : "";
        }
        return out.toString();
    }

    /**
     * 解析 parentPath + path 得到一个绝对路径（Linux 风格）。
     *
     * <p>语义：</p>
     * <ul>
     *   <li>若 path 以 '/' 开头，直接视为绝对路径并规范化。</li>
     *   <li>否则视为相对路径：要求 parentPath 为绝对路径，并将其与 child 拼接后再规范化。</li>
     * </ul>
     */
    public static String resolveLinuxPath(String parentPath, String path) {
        if (path == null) {
            throw new IllegalArgumentException("path cannot be null");
        }
        if (path.startsWith("/")) {
            return normalizeLinuxPath(path, true);
        }
        if (parentPath == null) {
            throw new IllegalArgumentException("relative path requires parentPath");
        }
        String parent = normalizeLinuxPath(parentPath, true);
        String child = normalizeLinuxPath(path, false);
        if (parent.equals("/")) {
            return "/" + child;
        }
        return parent + "/" + child;
    }

    /**
     * 校验单段名称（不能包含分隔符，不能是 '.'/'..'）。
     *
     * <p>用于校验 space/type/storeName/path segment 等“一个段”的场景。</p>
     */
    public static void validateSegment(String name, String what) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException(what + " cannot be empty");
        }
        if (name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException(what + " cannot be '.' or '..'");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(what + " cannot contain path separators");
        }
        if (name.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(what + " contains illegal character");
        }
    }

    /**
     * 把 dotted 名称（例如 a.b.c）转换为 Linux 风格路径（a/b/c）。
     *
     * <p>会校验每个 segment，禁止空段与非法段。</p>
     */
    public static String dottedToLinuxPath(String dotted, String what) {
        if (dotted == null || dotted.isEmpty()) {
            throw new IllegalArgumentException(what + " cannot be empty");
        }
        if (dotted.indexOf('/') >= 0 || dotted.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(what + " cannot contain path separators");
        }
        String[] parts = dotted.split("\\.", -1);
        StringBuilder sb = new StringBuilder(dotted.length() + 4);
        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i];
            if (seg.isEmpty()) {
                throw new IllegalArgumentException(what + " contains empty segment");
            }
            validateSegment(seg, what + " segment");
            if (i > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    /**
     * 将任意字符串转成文件名/目录名安全的短名（保留字母数字下划线减号点；其余按 UTF-8 bytes 做 short SHA-256 尾缀）。
     *
     * <p>目标：保证列名（@DsField.name 可能含中文/特殊字符）可用作 DsEqIndexStore 目录名，
     * 又在安全集合内保持可读。输出长度不超过 {@code maxLen}（按 chars 计），超长时头部截断，
     * 保证尾部 8 位 hex 校验位不丢。</p>
     */
    public static String toSafeFileName(String name, int maxLen) {
        if (name == null || name.isEmpty()) {
            return "empty";
        }
        StringBuilder safe = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                safe.append(c);
            } else {
                safe.append('_');
            }
        }
        String s = safe.length() == 0 ? "_" : safe.toString();
        String hash8 = shortSha8(name);
        if (s.length() + 1 + hash8.length() > maxLen) {
            int keep = Math.max(1, maxLen - 1 - hash8.length());
            s = s.substring(0, keep);
        }
        return s + "_" + hash8;
    }

    private static String shortSha8(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                int v = digest[i] & 0xFF;
                out.append(Character.forDigit(v >>> 4, 16));
                out.append(Character.forDigit(v & 0x0F, 16));
            }
            return out.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
