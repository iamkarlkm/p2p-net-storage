package com.q3lives.ds.enums;

/**
 * 注册表 Category：顶层数据物理形态分类（类比 Windows 注册表 REG_SZ / REG_BINARY / REG_DWORD 的
 * 最粗层级分类）。当系统底层需要知道"数据在物理上怎么存储/搜索"时，用 Category；业务上的具体含义用
 * {@link DataType} 细分。
 *
 * <p>本 Category 集合完全对齐用户原始设计中出现的 13 类物理形态：
 * <ul>
 *   <li>BINARY  二进制流（通用 bytes、Image、Audio、Video、Encrypted、Blob、Zip、Setup、Exec 等归档形态）</li>
 *   <li>STRING  字符串（sz、multi_sz、json、xml、char、datetime、text、uri、fs_path、tag、label、comment、registry_dir 等）</li>
 *   <li>NUMERIC 数值型（dword、qword、float、double、bigint、decimal、datetime/timestamp、date64、time64）</li>
 *   <li>COMPLEX 复杂结构（object/array/list/set/map）</li>
 *   <li>CONFIG  配置文件类（properties/xml/yml/json/toml/ini/conf）</li>
 *   <li>SCRIPT  脚本文件（js/ts/sh/bat/ps1）</li>
 *   <li>DATABASE 数据库对象（catalog/schema/table/view/routine/trigger/user/role/privilege/column/column_group）</li>
 *   <li>DOCS    文档形态（design/spec/docx/xlsx/pptx/pdf）</li>
 *   <li>ZIP     压缩包（保留 Category 位，业务后续补齐 REG_ZIP_* 枚举项）</li>
 *   <li>SETUP   安装包（保留 Category 位）</li>
 *   <li>EXEC    可执行程序（保留 Category 位）</li>
 *   <li>P2P     p2p-net-storage 常用数据类型和集合（保留 Category 位，业务后续补齐 REG_P2P_*）</li>
 *   <li>CODE    源代码文件（java/c/cpp/python…）</li>
 * </ul>
 */
public enum Category {
    BINARY("二进制流"),
    STRING("字符串"),
    NUMERIC("数值型"),
    COMPLEX("复杂结构"),
    CONFIG("配置文件"),
    SCRIPT("脚本"),
    DATABASE("数据库元对象"),
    DOCS("文档"),
    ZIP("压缩包"),
    SETUP("安装包"),
    EXEC("可执行程序"),
    P2P("p2p-net-storage 常用数据类型/集合"),
    CODE("源代码");

    private final String desc;
    public static final int BITS = 4; // 足够容纳 13 类

    Category(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }

    public long mask() {
        return 1L << ordinal();
    }
}
