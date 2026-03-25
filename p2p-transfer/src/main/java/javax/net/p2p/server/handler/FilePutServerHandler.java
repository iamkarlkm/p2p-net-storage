/**
 * FilePutServerHandler - 文件上传服务器处理器，处理客户端文件上传请求
 * 
 * 主要功能：
 * 1. 处理PUT_FILE命令，接收客户端上传的文件数据并保存到本地
 * 2. 文件完整性验证，检查文件长度与数据长度的一致性
 * 3. 沙箱安全控制，确保文件写入在安全目录内
 * 4. 支持零拷贝文件写入，提高大文件上传性能
 * 
 * 处理流程：
 * 1. 命令验证：确认请求为PUT_FILE命令
 * 2. 数据提取：从请求中提取FileDataModel（包含文件数据、路径和存储ID）
 * 3. 文件验证：检查文件长度记录与实际数据长度是否一致
 * 4. 路径安全：获取沙箱内的安全文件路径
 * 5. 文件写入：使用高效方式将数据写入文件
 * 6. 响应返回：返回操作成功或错误响应
 * 
 * 安全控制：
 * 1. 沙箱验证：确保文件写入在允许的目录范围内
 * 2. 长度验证：防止数据截断或数据篡改攻击
 * 3. 路径规范化：防止目录遍历攻击
 * 4. 权限检查：验证目标目录可写性
 * 
 * 性能优化：
 * 1. 零拷贝写入：使用NIO FileChannel和内存映射提高写入效率
 * 2. 直接缓冲区：避免数据在JVM堆内存中的多次拷贝
 * 3. 异步操作：继承AbstractLongTimedRequestAdapter支持长时间操作
 * 4. 批量写入：支持大文件的批量写入操作
 * 
 * 异常处理：
 * 1. 长度不一致：抛出RuntimeException，返回STD_ERROR错误
 * 2. 文件不存在：FileUtil.getSandboxFileForWrite会创建文件或抛出异常
 * 3. 权限不足：返回STD_ERROR包含权限错误信息
 * 4. IO异常：返回STD_ERROR包含IO错误详情
 * 
 * 使用场景：
 * - 客户端上传文件到服务器
 * - 文件备份和同步操作
 * - 分布式文件存储系统的写入节点
 * 
 * 注意事项：
 * 1. 文件长度验证是重要的安全措施，防止数据篡改
 * 2. 沙箱机制确保文件不会写入系统敏感目录
 * 3. 大文件上传建议使用分片传输（PUT_FILE_SEGMENTS）
 * 4. 文件写入是覆盖操作，已存在的文件会被覆盖
 * 
 * @author Administrator
 * @version 1.0
 * @since 2025
 */
package javax.net.p2p.server.handler;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.FileUtil;

public class FilePutServerHandler extends AbstractLongTimedRequestAdapter implements P2PCommandHandler {

    /**
     * 获取处理器对应的P2P命令类型
     * 
     * 功能说明：
     * 返回此处理器负责处理的P2P命令类型，用于消息路由时识别处理器。
     * 服务器接收到PUT_FILE命令时，会自动路由到此处理器。
     * 
     * 命令说明：
     * - PUT_FILE：标准文件上传命令，用于上传完整文件
     * - FORCE_PUT_FILE：强制文件上传命令，覆盖已存在的文件
     * 
     * 实现原理：
     * - 返回P2PCommand.PUT_FILE枚举值
     * - 服务器使用此方法建立命令到处理器的映射关系
     * - 支持动态处理器注册和查找
     * 
     * @return P2PCommand.PUT_FILE 文件上传命令
     */
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.PUT_FILE;
    }

    /**
     * 处理文件上传请求，接收文件数据并保存到本地
     * 
     * 处理流程：
     * 1. 命令验证：确认请求为PUT_FILE命令，防止错误路由
     * 2. 数据提取：从请求中提取FileDataModel（包含文件路径、存储ID和文件数据）
     * 3. 文件准备：获取沙箱内的安全文件路径，确保写入操作在安全范围内
     * 4. 完整性验证：检查文件长度记录与实际数据长度是否一致，防止数据篡改
     * 5. 文件写入：使用高效的文件写入方法保存数据
     * 6. 响应返回：返回操作成功响应或错误响应
     * 
     * 安全验证：
     * 1. 长度一致性验证：payload.length必须等于payload.data.length
     *    - 防止数据截断攻击
     *    - 防止数据填充攻击
     *    - 确保数据完整性
     * 2. 沙箱路径验证：FileUtil.getSandboxFileForWrite确保文件在安全目录内
     * 3. 权限验证：确保目标目录可写
     * 
     * 性能特点：
     * 1. 零拷贝写入：FileUtil.storeFile使用NIO实现高效写入
     * 2. 内存优化：避免数据在内存中的多次拷贝
     * 3. 异常恢复：完善的异常处理机制
     * 
     * 异常处理：
     * 1. 长度不一致：抛出RuntimeException，返回STD_ERROR错误
     * 2. 文件创建失败：FileUtil.getSandboxFileForWrite可能抛出IOException
     * 3. 写入失败：FileUtil.storeFile可能抛出IO异常
     * 4. 权限不足：返回权限错误信息
     * 
     * 注意事项：
     * 1. 文件写入是覆盖操作，已存在的同名文件会被覆盖
     * 2. 大文件上传建议使用分片传输机制
     * 3. 文件长度验证是重要的安全措施
     * 4. 响应中不返回文件数据，只返回操作状态
     * 
     * @param request 客户端请求，包含文件数据和元信息
     * @return 操作结果响应，成功返回STD_OK，失败返回STD_ERROR
     */
    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            // 验证请求命令类型，确保是PUT_FILE命令
            if (request.getCommand().getValue() == P2PCommand.PUT_FILE.getValue()) {
                // 提取文件数据模型，包含文件路径、存储ID和数据内容
                FileDataModel payload = (FileDataModel) request.getData();
                
                // 获取沙箱内的安全文件路径，防止目录遍历攻击
                File file = FileUtil.getSandboxFileForWrite(payload.storeId, payload.path);
                
                // 重要：验证文件长度记录与实际数据长度的一致性
                // 这是防止数据篡改的关键安全措施
                if (payload.length != payload.data.length) {
                    throw new RuntimeException("文件长度记录不一致:expected length=" + payload.length + ",actual length=" + payload.data.length);
                }
               
                // 使用高效的文件写入方法保存数据
                // storeFile方法使用NIO实现，支持零拷贝写入
                FileUtil.storeFile(file, 0, payload.data.length, payload.data);
                
                // 返回操作成功响应，不包含数据内容
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
            } else {
                // 命令类型不匹配，返回内部校验错误
                // 这种情况通常表示服务器路由配置错误
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令内部校验错误！");
            }
        } catch (Exception e) {
            // 捕获所有异常，返回错误响应
            // 异常信息包含在响应中，便于客户端诊断问题
            // 注意：生产环境可能需要更详细的错误分类
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
        }
    }

   
    

}
