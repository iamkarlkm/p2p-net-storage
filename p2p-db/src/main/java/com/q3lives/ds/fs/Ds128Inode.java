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
    public long data_ctime;
    // 8-byte data最近修改时间
    public long data_mtime;

    // 32-byte 短命名标识符存储 byte[0]-> value>0:短命名标识符有效,且存储标识符长度(不大于31);value==0:标识符长度>31,存储Big-endian DsString id,忽略byte[0],可存储最大56位id值
    public byte[] name = new byte[32];
    // 8-byte 父级inode
    public long inode_parent;
    // 8-byte 数据acl列表索引id
    public long i_acl_id;
    //4-byte 文件所有者ID
    public int i_uid;
    //4-byte 文件所有者组ID
    public int i_gid;
    // 8-byte inode 创建时间
    public long inode_ctime;
    // 8-byte inode 最近更新时间
    public long inode_mtime;
    // 8-byte file 原始(最初)生成时间 birth time
    public long i_birth_time;
   
    // 8-byte 配额top目录id,如果不会空,文件大小变化时，应更新该top目录容量数据。超过配额,停止操作,报异常。
    public long i_quota_id;
    // 8-byte 64位数据校验码 -- 文件系统 ,如果设置了完整性校验标志位,应以此校验数据。
    public long data_check_sum;

}
