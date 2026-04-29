
package com.q3lives.ds.fs;

import com.q3lives.ds.interfaces.DsTableByteBufferSerializable;
import java.nio.ByteBuffer;
import java.util.HashSet;

/**
 *
 * @author Administrator
 */
public class InheritedMetadata implements DsTableByteBufferSerializable{
    
    Long id;//inode id
    int flags;
    /**
     *  // flags 32 位 继承属性标志 位定义
    public static final short IFLAG_QUOTA = 0x0001;   // bit0 启用配额管理。
    public static final short IFLAG_ATIME = 0x0002;   // bit1  记录最近访问时间
    public static final short IFLAG_ACL = 0x0004;   // bit2 启用ACL列表管理。
    public static final short IFLAG_XATTR = 0x0008;   // bit3 扩展属性有效。
    public static final short IFLAG_AUDIT = 0x0010;   // bit4 启用审计日志。
    public static final short IFLAG_PUB_EVEENT = 0x0020;   // bit5 启用发布文件系统事件
    public static final short IFLAG_00 = 0x0040;   // bit6 no use 
    public static final short IFLAG_01 = 0x0080;   // bit7 no use

     */
    int gid;//owner 组ID。
    int uid;//owner 用户ID。
    int roleId;//owner 角色ID。
    
    HashSet<Long> acls = new HashSet();//权限列表。采用默认拒绝模式。只要不在这个列表中的都拒绝。高32位掩码。区分组ID,用户ID和角色ID。
    /**
     *  //  acls 高32位掩码 位定义
    public static final short IFLAG_USER = 0x000100000000;   // bit0 用户ID。
    public static final short IFLAG_USER = 0x000200000000;   // bit1 组ID。
    public static final short IFLAG_USER = 0x000400000000;   // bit2 角色ID。
     */

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public void setId(long id) {
        this.id = id;
    }

    @Override
    public ByteBuffer toBytes() {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    @Override
    public void load(ByteBuffer data) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }
    
}
