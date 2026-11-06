package com.q3lives.ds.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 注册表业务类型 DataType（REG_* 常量 + code 16-bit + seq 48-bit 位运算 id）。
 *
 * <p><b>实现选择说明（Performance First）</b>：不使用 Java enum，原因：
 * <ul>
 *   <li>用户自定义 DataType 需要 code bit15=1，Java enum values() 集合不可动态扩展；</li>
 *   <li>若走反射强行 new enum 常量，违反"最小集 / 零不必要开销"硬约束。</li>
 * </ul>
 * 因此 DataType 采用普通类 + 系统预定义项为 {@code public static final DataType} 常量的经典实现。
 * 语义、命名、REG_ 前缀、位运算规则与用户原文的 enum 设计一一对应，可逐字回归。
 *
 * <p>ID 位布局：<pre>
 *    63          48        0
 *   +-------------+----------+
 *   | code (16b)  | sequenceId (48b) |
 *   +-------------+----------+
 * code bit15 == 0 → 系统预定义 REG_* 常量；== 1 → 用户自定义动态注册。
 * </pre>
 */
public final class DataType {

    private static final int CODE_BITS = 16;
    public static final int SEQ_BITS = 64 - CODE_BITS;
    private static final long SEQ_MASK = (1L << SEQ_BITS) - 1;
    public static final int USER_DEFINED_FLAG = 0x8000;
    public static final int INLINE_PACKED_LENGTH_HINT_SHIFT = 56; // long 最高位字节存 small BINARY 的 len（1-8），剩余 56bit/7 byte 存 payload（len<=7 时或 NUMERIC）
    private static final int SYSTEM_CODE_MAX = USER_DEFINED_FLAG - 1;
    private static final int MAX_KEY_LEN_BYTES = 4096;

    private final Category category;
    private final int code;
    private final String desc;
    private final String name;

    private DataType(String name, Category category, int code, String desc) {
        this.name = name;
        this.category = category;
        this.code = code;
        this.desc = desc;
    }

    public Category getCategory() { return category; }
    public int getCode() { return code; }
    public String getDesc() { return desc; }
    public String name() { return name; }

    public boolean isUserDefined() { return (code & USER_DEFINED_FLAG) != 0; }

    public long generateId(long sequenceId) {
        return (((long) code) << SEQ_BITS) | (sequenceId & SEQ_MASK);
    }

    // ================= 系统预定义 REG_* 常量（按用户原文逐项落地）=================

    private static final HashSet<Integer> USED_CODES = new HashSet<>();
    private static int pickCode(int desired) {
        if (desired < 0 || desired > SYSTEM_CODE_MAX) {
            throw new IllegalArgumentException("系统预定义 code 必须 0..0x7FFF，实际 " + desired);
        }
        int c = desired;
        while (USED_CODES.contains(c)) {
            if (c >= SYSTEM_CODE_MAX) {
                throw new IllegalStateException("系统预定义 code 冲突过多，desired=0x"
                        + Integer.toHexString(desired) + " 无法顺延，请手动重分配");
            }
            c++;
        }
        USED_CODES.add(c);
        return c;
    }

    private static DataType sys(String name, Category cat, int desiredCode, String desc) {
        return new DataType(name, cat, pickCode(desiredCode), desc);
    }

    // ========== BINARY
    public static final DataType REG_BINARY       = sys("REG_BINARY",       Category.BINARY,   0x00, "通用二进制数据");
    public static final DataType REG_IMAGE        = sys("REG_IMAGE",        Category.BINARY,   0x01, "图片/多媒体文件");
    public static final DataType REG_AUDIO        = sys("REG_AUDIO",        Category.BINARY,   0x02, "音频/多媒体文件");
    public static final DataType REG_VIDEO        = sys("REG_VIDEO",        Category.BINARY,   0x03, "视频/多媒体文件");
    public static final DataType REG_ENCRYPTED    = sys("REG_ENCRYPTED",    Category.BINARY,   0x05, "加密密文数据");
    public static final DataType REG_BLOB         = sys("REG_BLOB",         Category.BINARY,   0x06, "二进制大对象");

    // ========== STRING
    public static final DataType REG_SZ             = sys("REG_SZ",             Category.STRING, 0x10, "标准字符串");
    public static final DataType REG_MULTI_SZ       = sys("REG_MULTI_SZ",       Category.STRING, 0x11, "多行字符串/列表");
    public static final DataType REG_JSON           = sys("REG_JSON",           Category.STRING, 0x12, "JSON 结构化文本");
    public static final DataType REG_XML            = sys("REG_XML",            Category.STRING, 0x13, "XML 结构化文本");
    public static final DataType REG_CHAR           = sys("REG_CHAR",           Category.STRING, 0x14, "7bytes定长char(64位宽,前8位指示实占byte)");
    public static final DataType REG_CHAR_DATETIME  = sys("REG_CHAR_DATETIME",  Category.STRING, 0x15, "日期时间标准字符串yyyy-MM-dd hh:mm:ss");
    public static final DataType REG_TEXT           = sys("REG_TEXT",           Category.STRING, 0x16, "文本大对象");
    public static final DataType REG_URI            = sys("REG_URI",            Category.STRING, 0x17, "(Uniform Resource Locator)统一资源定位符");
    public static final DataType REG_FS_PATH        = sys("REG_FS_PATH",        Category.STRING, 0x18, "文件系统路径");
    public static final DataType REG_TAG            = sys("REG_TAG",            Category.STRING, 0x19, "标签(多对多)");
    public static final DataType REG_REGISTRY_DIR   = sys("REG_REGISTRY_DIR",   Category.STRING, 0x1A, "通用注册表目录");
    public static final DataType REG_LABEL          = sys("REG_LABEL",          Category.STRING, 0xA0, "称谓/归类(单-指称)");
    public static final DataType REG_COMMENT        = sys("REG_COMMENT",        Category.STRING, 0xA1, "注释");

    // ========== NUMERIC
    public static final DataType REG_DWORD      = sys("REG_DWORD",      Category.NUMERIC, 0x20, "32位整数");
    public static final DataType REG_QWORD      = sys("REG_QWORD",      Category.NUMERIC, 0x21, "64位整数");
    public static final DataType REG_FLOAT      = sys("REG_FLOAT",      Category.NUMERIC, 0x22, "32位浮点数");
    public static final DataType REG_DOUBLE     = sys("REG_DOUBLE",     Category.NUMERIC, 0x23, "64位浮点数");
    public static final DataType REG_BIGINT     = sys("REG_BIGINT",     Category.NUMERIC, 0x24, "变长整数");
    public static final DataType REG_DECIMAL    = sys("REG_DECIMAL",    Category.NUMERIC, 0x25, "准确计算精度的数字");
    public static final DataType REG_DATETIME   = sys("REG_DATETIME",   Category.NUMERIC, 0x26, "日期时间");
    public static final DataType REG_TIMESTAMP  = sys("REG_TIMESTAMP",  Category.NUMERIC, 0x27, "时间戳");
    public static final DataType REG_DATE64     = sys("REG_DATE64",     Category.NUMERIC, 0x28, "64位日期");
    public static final DataType REG_TIME64     = sys("REG_TIME64",     Category.NUMERIC, 0x29, "64位时间");

    // ========== COMPLEX
    public static final DataType REG_OBJECT = sys("REG_OBJECT", Category.COMPLEX, 0x30, "自定义对象/实体");
    public static final DataType REG_ARRAY  = sys("REG_ARRAY",  Category.COMPLEX, 0x31, "数组/集合");
    public static final DataType REG_LIST   = sys("REG_LIST",   Category.COMPLEX, 0x32, "列表/集合");
    public static final DataType REG_SET    = sys("REG_SET",    Category.COMPLEX, 0x33, "不含重复项/集合");
    public static final DataType REG_MAP    = sys("REG_MAP",    Category.COMPLEX, 0x35, "KV映射/集合");

    // ========== CONFIG
    public static final DataType REG_CONFIG_PROPERTIES = sys("REG_CONFIG_PROPERTIES", Category.CONFIG, 0x40, "属性配置文件");
    public static final DataType REG_CONFIG_XML       = sys("REG_CONFIG_XML",       Category.CONFIG, 0x41, "xml配置文件");
    public static final DataType REG_CONFIG_YML       = sys("REG_CONFIG_YML",       Category.CONFIG, 0x42, "yml配置文件");
    public static final DataType REG_CONFIG_JSON      = sys("REG_CONFIG_JSON",      Category.CONFIG, 0x43, "json配置文件");
    public static final DataType REG_CONFIG_TOML      = sys("REG_CONFIG_TOML",      Category.CONFIG, 0x44, "toml配置文件");
    public static final DataType REG_CONFIG_INI       = sys("REG_CONFIG_INI",       Category.CONFIG, 0x45, "ini配置文件");
    public static final DataType REG_CONFIG_CONF      = sys("REG_CONFIG_CONF",      Category.CONFIG, 0x46, "conf配置文件");

    // ========== SCRIPT
    public static final DataType REG_JAVASCRIPT  = sys("REG_JAVASCRIPT",  Category.SCRIPT, 0x50, "JS脚本");
    public static final DataType REG_TYPESCRIPT  = sys("REG_TYPESCRIPT",  Category.SCRIPT, 0x52, "Typed JS脚本");
    public static final DataType REG_SCRIPT_SH   = sys("REG_SCRIPT_SH",   Category.SCRIPT, 0x53, "Shell脚本");
    public static final DataType REG_SCRIPT_BAT  = sys("REG_SCRIPT_BAT",  Category.SCRIPT, 0x54, "windows脚本");
    public static final DataType REG_SCRIPT_PS   = sys("REG_SCRIPT_PS",   Category.SCRIPT, 0x55, "power shell脚本");

    // ========== DATABASE
    public static final DataType REG_DATABASE_CATALOG       = sys("REG_DATABASE_CATALOG",       Category.DATABASE, 0x60, "数据库目录/容器");
    public static final DataType REG_DATABASE_SCHEMA        = sys("REG_DATABASE_SCHEMA",        Category.DATABASE, 0x61, "数据库");
    public static final DataType REG_DATABASE_TABLE         = sys("REG_DATABASE_TABLE",         Category.DATABASE, 0x62, "数据库表");
    public static final DataType REG_DATABASE_VIEW          = sys("REG_DATABASE_VIEW",          Category.DATABASE, 0x63, "数据库视图");
    public static final DataType REG_DATABASE_ROUTINE       = sys("REG_DATABASE_ROUTINE",       Category.DATABASE, 0x64, "数据库存储过程/函数");
    public static final DataType REG_DATABASE_TRIGGER       = sys("REG_DATABASE_TRIGGER",       Category.DATABASE, 0x65, "数据库触发器");
    public static final DataType REG_DATABASE_USER          = sys("REG_DATABASE_USER",          Category.DATABASE, 0x66, "数据库用户");
    public static final DataType REG_DATABASE_ROLE          = sys("REG_DATABASE_ROLE",          Category.DATABASE, 0x67, "数据库角色");
    public static final DataType REG_DATABASE_PRIVILEGE     = sys("REG_DATABASE_PRIVILEGE",     Category.DATABASE, 0x68, "数据库权限");
    public static final DataType REG_DATABASE_COLUMN        = sys("REG_DATABASE_COLUMN",        Category.DATABASE, 0x69, "数据库字段");
    public static final DataType REG_DATABASE_COLUMN_GROUP  = sys("REG_DATABASE_COLUMN_GROUP",  Category.DATABASE, 0x6A, "数据库字段分组");

    // ========== DOCS
    public static final DataType REG_DOCS_DESIGN = sys("REG_DOCS_DESIGN", Category.DOCS, 0x70, "设计文档");
    public static final DataType REG_DOCS_SPEC   = sys("REG_DOCS_SPEC",   Category.DOCS, 0x71, "规格说明文档");
    public static final DataType REG_DOCS_DOCX   = sys("REG_DOCS_DOCX",   Category.DOCS, 0x72, "word文字处理文档");
    public static final DataType REG_DOCS_XLSX   = sys("REG_DOCS_XLSX",   Category.DOCS, 0x73, "excel表格文档");
    public static final DataType REG_DOCS_PPTX   = sys("REG_DOCS_PPTX",   Category.DOCS, 0x74, "ppt演示文档");
    public static final DataType REG_DOCS_PDF    = sys("REG_DOCS_PDF",    Category.DOCS, 0x75, "PDF文档");

    // ========== CODE
    public static final DataType REG_CODE_JAVA   = sys("REG_CODE_JAVA",   Category.CODE, 0xC0, "java语言");
    public static final DataType REG_CODE_C      = sys("REG_CODE_C",      Category.CODE, 0xC1, "c语言");
    public static final DataType REG_CODE_CPP    = sys("REG_CODE_CPP",    Category.CODE, 0xC2, "c++语言");
    public static final DataType REG_CODE_PYTHON = sys("REG_CODE_PYTHON", Category.CODE, 0xC3, "python语言");

    // ========== 内部 DsRegistry 专用：外部 PAYLOAD 引用指针（STRING/BINARY typedId 存 DsHashMap.value）
    static final DataType REG_VALUEPAYLOAD_PTR = sys("REG_VALUEPAYLOAD_PTR", Category.COMPLEX, 0x7E, "内部用: valueOrId 指向外部分区 typedId");

    // ================= 系统预定义集合 + 查找 =================

    private static final List<DataType> SYSTEM_VALUES = Collections.unmodifiableList(Arrays.asList(
            REG_BINARY, REG_IMAGE, REG_AUDIO, REG_VIDEO, REG_ENCRYPTED, REG_BLOB,
            REG_SZ, REG_MULTI_SZ, REG_JSON, REG_XML, REG_CHAR, REG_CHAR_DATETIME, REG_TEXT, REG_URI, REG_FS_PATH, REG_TAG, REG_REGISTRY_DIR, REG_LABEL, REG_COMMENT,
            REG_DWORD, REG_QWORD, REG_FLOAT, REG_DOUBLE, REG_BIGINT, REG_DECIMAL, REG_DATETIME, REG_TIMESTAMP, REG_DATE64, REG_TIME64,
            REG_OBJECT, REG_ARRAY, REG_LIST, REG_SET, REG_MAP,
            REG_CONFIG_PROPERTIES, REG_CONFIG_XML, REG_CONFIG_YML, REG_CONFIG_JSON, REG_CONFIG_TOML, REG_CONFIG_INI, REG_CONFIG_CONF,
            REG_JAVASCRIPT, REG_TYPESCRIPT, REG_SCRIPT_SH, REG_SCRIPT_BAT, REG_SCRIPT_PS,
            REG_DATABASE_CATALOG, REG_DATABASE_SCHEMA, REG_DATABASE_TABLE, REG_DATABASE_VIEW, REG_DATABASE_ROUTINE, REG_DATABASE_TRIGGER, REG_DATABASE_USER, REG_DATABASE_ROLE, REG_DATABASE_PRIVILEGE, REG_DATABASE_COLUMN, REG_DATABASE_COLUMN_GROUP,
            REG_DOCS_DESIGN, REG_DOCS_SPEC, REG_DOCS_DOCX, REG_DOCS_XLSX, REG_DOCS_PPTX, REG_DOCS_PDF,
            REG_CODE_JAVA, REG_CODE_C, REG_CODE_CPP, REG_CODE_PYTHON,
            REG_VALUEPAYLOAD_PTR
    ));

    private static final Map<Integer, DataType> SYSTEM_CODE_MAP = SYSTEM_VALUES.stream()
            .collect(Collectors.collectingAndThen(
                    Collectors.toUnmodifiableMap(DataType::getCode, Function.identity()),
                    Function.identity()));

    private static final Map<String, DataType> SYSTEM_NAME_MAP = SYSTEM_VALUES.stream()
            .collect(Collectors.collectingAndThen(
                    Collectors.toUnmodifiableMap(DataType::name, Function.identity()),
                    Function.identity()));

    private static final ConcurrentHashMap<Integer, DataType> CUSTOM_MAP = new ConcurrentHashMap<>();

    public static List<DataType> systemValues() { return SYSTEM_VALUES; }

    public static Optional<DataType> valueOf(String name) {
        return Optional.ofNullable(SYSTEM_NAME_MAP.get(name));
    }

    public static DataType parseFromId(long id) {
        int code = (int) (id >>> SEQ_BITS);
        DataType dt = SYSTEM_CODE_MAP.get(code);
        if (dt != null) return dt;
        dt = CUSTOM_MAP.get(code);
        return dt != null ? dt : REG_BINARY;
    }

    public static Optional<DataType> getByCode(int code) {
        DataType dt = SYSTEM_CODE_MAP.get(code);
        if (dt != null) return Optional.of(dt);
        return Optional.ofNullable(CUSTOM_MAP.get(code));
    }

    /**
     * 注册用户自定义 DataType：code bit15 = 1（即 >= 0x8000）。
     *
     * @param code 必须 >= 0x8000 且 未被注册
     * @return 新 DataType 实例
     */
    public static synchronized DataType registerUserDefined(int code, Category category, String name, String desc) {
        if ((code & USER_DEFINED_FLAG) == 0) {
            throw new IllegalArgumentException("User-defined DataType code 必须设置 bit15=1 (>=0x8000), actual=" + code);
        }
        if (category == null) throw new NullPointerException("category");
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name 不能为 null/empty");
        if (CUSTOM_MAP.containsKey(code) || SYSTEM_CODE_MAP.containsKey(code)) {
            throw new IllegalArgumentException("DataType code=0x" + Integer.toHexString(code) + " 已被注册");
        }
        DataType dt = new DataType(name, category, code, desc);
        CUSTOM_MAP.put(code, dt);
        return dt;
    }

    /**
     * 用户自定义类型的 name 推荐前缀（非强制）：CUSTOM_REG_xxx。
     */
    public static String customName(String suffix) {
        return "CUSTOM_REG_" + suffix;
    }

    public static int maxKeyLengthBytes() {
        return MAX_KEY_LEN_BYTES;
    }

    @Override
    public String toString() {
        return name + "(code=0x" + Integer.toHexString(code) + "," + category + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataType)) return false;
        DataType dt = (DataType) o;
        return code == dt.code && name.equals(dt.name);
    }

    @Override
    public int hashCode() {
        return 31 * code + name.hashCode();
    }
}
