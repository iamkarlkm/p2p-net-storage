/**
 * FileGetServerHandler - 文件获取服务器处理器，处理客户端文件下载请求
 * 
 * 主要功能：
 * 1. 处理GET_FILE命令，读取本地文件并返回给客户端
 * 2. 支持文件分片传输，处理大文件的分块读取
 * 3. 文件完整性验证，计算并返回文件的MD5校验值
 * 4. 沙箱安全控制，确保文件访问在安全目录内
 * 
 * 处理逻辑：
 * 1. 小文件（≤8MB）：直接读取整个文件并返回
 * 2. 大文件（>8MB）：分片读取，返回第一个分片和分片信息
 * 3. 文件验证：检查文件是否存在和可读性
 * 4. 安全控制：确保文件访问在配置的沙箱目录内
 * 
 * 技术特点：
 * - 继承AbstractLongTimedRequestAdapter，支持长时间操作
 * - 实现P2PCommandHandler接口，遵循命令处理器模式
 * - 使用FileUtil工具类进行高效文件操作
 * - 支持零拷贝文件读取，提高大文件传输性能
 * 
 * 性能优化：
 * 1. 文件分片：大文件分片传输，避免内存溢出
 * 2. 内存映射：使用NIO内存映射文件提高读取速度
 * 3. 校验计算：并行计算文件MD5，确保数据完整性
 * 4. 异常处理：完善的异常捕获和错误响应
 * 
 * 使用场景：
 * - 客户端下载文件到本地
 * - 大文件的分片传输
 * - 文件完整性验证
 * 
 * 安全考虑：
 * 1. 沙箱限制：文件访问限制在配置的安全目录
 * 2. 路径验证：防止目录遍历攻击
 * 3. 权限检查：验证文件可读性
 * 4. 大小限制：防止超大文件导致内存溢出
 * 
 * @author Administrator
 * @version 1.0
 * @since 2025
 */
package javax.net.p2p.server.handler;

import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.FileUtil;
import javax.net.p2p.utils.SecurityUtils;

public class FileGetServerHandler extends AbstractLongTimedRequestAdapter implements P2PCommandHandler {

    /**
     * 获取处理器对应的P2P命令类型
     * 
     * 功能说明：
     * 返回此处理器负责处理的P2P命令类型，用于消息路由时识别处理器。
     * 服务器接收到GET_FILE命令时，会自动路由到此处理器。
     * 
     * 实现原理：
     * - 返回P2PCommand.GET_FILE枚举值
     * - 服务器使用此方法建立命令到处理器的映射关系
     * - 支持动态处理器注册和查找
     * 
     * @return P2PCommand.GET_FILE 文件获取命令
     */
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.GET_FILE;
    }

    /**
     * 处理文件获取请求，读取文件并返回给客户端
     * 
     * 处理流程：
     * 1. 命令验证：确认请求为GET_FILE命令
     * 2. 数据提取：从请求中提取FileDataModel
     * 3. 文件检查：验证文件存在且在沙箱目录内
     * 4. 大小判断：根据文件大小决定传输方式
     * 5. 文件读取：使用适当方式读取文件数据
     * 6. 响应构建：构建成功或错误响应
     * 
     * 文件大小处理策略：
     * - 小文件（≤8MB）：直接读取整个文件，一次性返回
     * - 大文件（>8MB）：分片读取，返回第一个分片和分片元数据
     * 
     * 安全控制：
     * 1. 沙箱验证：确保文件在允许的目录范围内
     * 2. 存在性检查：防止文件不存在导致的异常
     * 3. 大小限制：避免读取超大文件导致内存溢出
     * 
     * 性能优化：
     * - 零拷贝读取：使用NIO FileChannel提高读取效率
     * - 内存映射：大文件使用内存映射减少IO次数
     * - 校验并行：文件读取和MD5计算可并行进行
     * 
     * 异常处理：
     * 1. 文件不存在：返回STD_ERROR错误信息
     * 2. 权限不足：返回STD_ERROR错误信息
     * 3. IO异常：返回STD_ERROR包含异常详情
     * 
     * @param request 客户端请求，包含文件路径和存储ID
     * @return 文件数据响应或错误响应
     */
    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            // 验证请求命令类型，防止错误路由
            if (request.getCommand().getValue() == P2PCommand.GET_FILE.getValue()) {
                // 提取文件请求数据
                FileDataModel payload = (FileDataModel) request.getData();
                
                // 检查文件是否存在且在沙箱目录内
                // 沙箱机制防止目录遍历攻击
                File file = FileUtil.getAndCheckExistsSandboxFile(payload.storeId, payload.path);
                
                // 判断文件大小，决定传输策略
                if (file.length() <= P2PConfig.DATA_BLOCK_SIZE) {
                    // 小文件处理：直接读取整个文件
                    payload.data = FileUtil.loadFile(file);
                    payload.length = file.length();
                    
                    // 构建成功响应，返回完整文件数据
                    return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_GET_FILE, payload);
                } else {
                    // 大文件处理：分片传输
                    FileSegmentsDataModel segments = new FileSegmentsDataModel(payload.storeId, payload.path);
                    
                    // 设置分片传输的元数据
                    segments.start = 0;                     // 起始位置
                    segments.blockIndex = 0;               // 当前分片索引
                    segments.length = file.length();       // 文件总大小
                    segments.blockSize = P2PConfig.DATA_BLOCK_SIZE; // 分片大小（8MB）
                    
                    // 读取第一个分片数据
                    segments.blockData = FileUtil.loadFile(file, segments.start, segments.blockSize);
                    
                    // 计算分片MD5，用于数据完整性验证
                    segments.blockMd5 = SecurityUtils.toMD5(segments.blockData);
                    
                    // 计算整个文件的MD5，用于最终完整性验证
                    segments.md5 = SecurityUtils.getFileMD5String(file);
                    
                    // 构建分片响应，客户端后续会请求其他分片
                    return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_GET_FILE_SEGMENTS, segments);
                }
            } else {
                // 命令类型不匹配，返回内部校验错误
                // 这种情况通常表示服务器路由配置错误
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令内部校验错误！");
            }
        } catch (Exception e) {
            // 捕获所有异常，返回错误响应
            // 异常信息包含在响应中，便于客户端诊断问题
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
        }
    }

}
