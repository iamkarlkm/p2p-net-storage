package com.q3lives.ds.constant;

public class InodeConsts {

    // ================== i_mode 文件类型（高4位）==================
    public static final short S_IFILE = (short) 0x8000;  // 普通文件
    public static final short S_IFDIR = 0x4000;  // 目录
    public static final short S_IFLNK = (short) 0xA000;  // 符号链接
    public static final short S_IFCHR = 0x6000;  // 字符设备
    public static final short S_IFBLK = 0x2000;  // 块设备
    public static final short S_IFIFO = 0x1000;  // FIFO
    public static final short S_IFSOCK = (short) 0xC000;  // Socket

    // ================== 权限（低12位）==================
    public static final short S_IRWXU = 00700;
    public static final short S_IRUSR = 00400;
    public static final short S_IWUSR = 00200;
    public static final short S_IXUSR = 00100;

    public static final short S_IRWXG = 00070;
    public static final short S_IRGRP = 00040;
    public static final short S_IWGRP = 00020;
    public static final short S_IXGRP = 00010;

    public static final short S_IRWXO = 00007;
    public static final short S_IROTH = 00004;
    public static final short S_IWOTH = 00002;
    public static final short S_IXOTH = 00001;

    //  16 位 i_flags 位定义
    public static final short IFLAG_SECRM = 0x0001;   // bit0 安全删除
    public static final short IFLAG_SYNC = 0x0002;   // bit1 同步IO
    public static final short IFLAG_IMMUTABLE = 0x0004;   // bit2 不可修改
    public static final short IFLAG_APPEND = 0x0008;   // bit3 仅追加
    public static final short IFLAG_NOATIME = 0x0010;   // bit4 不更新atime
    public static final short IFLAG_INDEX = 0x0020;   // bit5 no use 目录哈希索引
    public static final short IFLAG_EXTENTS = 0x0040;   // bit6 no use 使用Extent
    public static final short IFLAG_ENCRYPT = 0x0080;   // bit7 加密
// 新增现代flags
    public static final short IFLAG_COMPRESSED = 0x0100;//压缩文件
    public static final short IFLAG_VERITY = 0x0200; //完整性校验（fs-verity）
    public static final short IFLAG_CASEFOLD = 0x0400; // 大小写不敏感
    public static final short IFLAG_NOCOW = 0x0800;  // 禁止写时复制（Btrfs/ext4）
    public static final short IFLAG_ARRCHIVED = 0x1000;//已归档
    public static final short IFLAG_SPARSE = 0x2000; //稀疏文件
    public static final short IFLAG_SEGMENT = 0x4000; //分段文件 -> 超过bucket最大块大小，需分段或流式或内存映射读取。
    public static final short IFLAG_AUDIT = (short) 0x8000; //启用审计。

}
