package com.q3lives.ds.fs;

public class Ds128Inode {

    // 4-byte 引用计数,为零表示已删除,可回收使用
    public int ref_count;
    // 2-byte linux兼容属性位
    public short i_mode;
    //2-byte 标志位
    public short i_flags;
    
    //8-byte 文件大小
    public long data_size;

    // 8-byte data创建时间
    public long data_ctime;//TODO这里可以记录数据最初生成时间:birth time,inode_ctime一般与这个时间是相同的,可以统一和省略
    // 8-byte data最近修改时间
    public long data_mtime;

    // 32-byte 短命名标识符存储 byte[0]-> value>0:短命名标识符有效,且存储标识符长度(不大于31);value==0:标识符长度>31,存储Big-endian DsString id,忽略byte[0],可存储最大56位id值
    public byte[] name = new byte[32];
    // 8-byte 父级inode
    public long inode_parent;
   
    public long bucket_id;//8-byte底层bucket存储id
    
    // 8-byte inode 创建时间
    public long inode_ctime;
    // 8-byte inode 最近更新时间
    public long inode_mtime;
    // 8-byte file acl 权限列表对象ID -> root uid=0,root gid=0; 如果为0,则默认无权限控制。
    public long i_acl_id;//TODO
   
    // 8-byte 管理目录ID(集中实现各种管理功能:acl列表,uid,gid,quota,atime,xattr,audit,文件系统事件订阅) 配额top目录id,如果不会空,文件大小变化时，应更新该top目录容量数据。超过配额,停止操作,报异常。
    public long i_inherited_mgr_id;
    // 8-byte 64位数据校验码 -- 文件系统 ,如果设置了完整性校验标志位,应以此校验数据。
    public long data_check_sum;
    
    /**
     *  //  16 位 i_flags 位定义
    public static final short IFLAG_SECRM = 0x0001;   // bit0 安全删除
    public static final short IFLAG_SYNC = 0x0002;   // bit1 同步IO
    public static final short IFLAG_IMMUTABLE = 0x0004;   // bit2 不可修改
    public static final short IFLAG_APPEND = 0x0008;   // bit3 仅追加
    public static final short IFLAG_NOATIME = 0x0010;   // bit4 不更新atime
    public static final short IFLAG_HIDDEN = 0x0020;   // bit5 隐藏文件
    public static final short IFLAG_SYSTEM = 0x0040;   // bit6 系统文件
    public static final short IFLAG_ENCRYPT = 0x0080;   // bit7 加密
// 新增现代flags
    public static final short IFLAG_COMPRESSED = 0x0100;//压缩文件
    public static final short IFLAG_VERITY = 0x0200; //完整性校验（fs-verity）
    public static final short IFLAG_CASEFOLD = 0x0400; // 大小写不敏感
    public static final short IFLAG_NOCOW = 0x0800;  // 禁止写时复制（Btrfs/ext4）
    public static final short IFLAG_ARRCHIVED = 0x1000;//已归档
    public static final short IFLAG_SPARSE = 0x2000; //稀疏文件
    public static final short IFLAG_SEGMENT = 0x4000; //分段文件 -> 超过bucket最大块大小，需分段或流式或内存映射读取。
    public static final short IFLAG_AUDIT = (short) 0x8000; //启用审计。已经转移到继承属性管理去了。
     */

}
