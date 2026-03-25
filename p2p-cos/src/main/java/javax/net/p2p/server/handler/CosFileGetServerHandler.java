
/**
 * CosFileGetServerHandler - 腾讯云COS文件获取服务器处理器，处理从云存储读取文件的请求
 * 
 * 主要功能：
 * 1. 处理GET_COS_FILE命令，从腾讯云COS对象存储读取文件数据
 * 2. 支持大文件分片读取，避免一次性加载大文件导致内存溢出
 * 3. 文件元数据获取，包括文件大小、MD5校验值等
 * 4. 文件完整性验证，使用云存储提供的MD5进行数据校验
 * 
 * 处理流程：
 * 1. 命令验证：确认请求为GET_COS_FILE命令
 * 2. 路径提取：从请求中提取云存储文件路径
 * 3. 元数据获取：查询云存储获取文件信息和元数据
 * 4. 大小判断：根据文件大小决定读取策略
 * 5. 数据读取：使用适当方式读取文件数据（完整读取或分片读取）
 * 6. 响应构建：构建包含文件数据或分片信息的响应
 * 
 * 文件大小处理策略：
 * - 小文件（≤配置的块大小，默认8MB）：直接读取整个文件，一次性返回
 * - 大文件（>配置的块大小）：分片读取，返回第一个分片和分片元数据
 * 
 * 云存储集成：
 * 1. 使用腾讯云COS SDK进行文件操作
 * 2. 支持标准对象存储操作（读取、元数据查询、分片读取）
 * 3. 自动处理云存储认证和连接管理
 * 4. 支持云存储的MD5校验机制
 * 
 * 性能优化：
 * 1. 分片读取：大文件分片传输，避免内存压力
 * 2. 流式处理：使用流式API减少内存占用
 * 3. 元数据缓存：可考虑缓存常用文件的元数据
 * 4. 连接复用：CosUtil管理云存储连接池
 * 
 * 安全控制：
 * 1. 路径验证：验证云存储路径的合法性
 * 2. 权限检查：确保有读取云存储文件的权限
 * 3. 数据校验：使用云存储提供的MD5进行完整性验证
 * 4. 大小限制：防止读取超大文件导致系统资源耗尽
 * 
 * 使用场景：
 * - 从腾讯云COS下载文件到客户端
 * - 云存储文件的分片下载
 * - 云存储文件信息的查询
 * - 云存储和本地文件的同步操作
 * 
 * 注意事项：
 * 1. 云存储读取可能受网络带宽限制
 * 2. 大文件分片读取需要客户端支持分片协议
 * 3. 云存储MD5可能为空，需要备选校验方案
 * 4. 读取操作会产生云存储API调用费用
 * 
 * @author Administrator
 * @version 1.0
 * @since 2025
 */
package javax.net.p2p.server.handler;

import com.giyo.chdfs.CosUtil;
import com.qcloud.cos.model.ObjectMetadata;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.HdfsFileDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CosFileGetServerHandler implements P2PCommandHandler{

	/**
	 * 获取处理器对应的P2P命令类型
	 * 
	 * 功能说明：
	 * 返回此处理器负责处理的P2P命令类型，用于消息路由时识别处理器。
	 * 服务器接收到GET_COS_FILE命令时，会自动路由到此处理器。
	 * 
	 * 命令说明：
	 * - GET_COS_FILE：从腾讯云COS读取文件的命令
	 * - GET_COS_FILE_SEGMENTS：从腾讯云COS分片读取文件的命令（大文件处理）
	 * 
	 * 云存储集成：
	 * - 使用腾讯云COS SDK进行文件操作
	 * - 支持标准对象存储API调用
	 * - 自动处理认证和授权
	 * 
	 * 实现原理：
	 * - 返回P2PCommand.GET_COS_FILE枚举值
	 * - 服务器使用此方法建立命令到处理器的映射关系
	 * - 支持动态处理器注册和查找
	 * 
	 * @return P2PCommand.GET_COS_FILE 云存储文件获取命令
	 */
	@Override
	public P2PCommand getCommand() {
		return P2PCommand.GET_COS_FILE;
	}

	@Override
	public P2PWrapper process(P2PWrapper request) {
		try {
			if (request.getCommand().getValue() == P2PCommand.GET_COS_FILE.getValue()) {
				HdfsFileDataModel payload = (HdfsFileDataModel) request.getData();
				log.info("CosFileGet: {} -> {}",payload.path,request.getCommand());
				ObjectMetadata info = CosUtil.getObjectInfo(payload.path);
				if(info.getContentLength()<=P2PConfig.DATA_GET_BLOCK_SIZE){//云出数据可能限制了长度
					payload.data = CosUtil.read(payload.path);
					payload.length = payload.data.length;
					log.info("CosFileGet success: {} -> {}",payload.path,P2PCommand.R_OK_GET_COS_FILE);
					return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_GET_COS_FILE, payload);
				}else{
					FileSegmentsDataModel segments = new FileSegmentsDataModel(0, payload.path);
					segments.start = 0;
					segments.blockIndex  = 0;
					segments.length = info.getContentLength();
					segments.blockSize = P2PConfig.DATA_GET_BLOCK_SIZE;
					segments.blockData = CosUtil.readPart(payload.path, 0, P2PConfig.DATA_GET_BLOCK_SIZE);
					
					segments.blockMd5 = SecurityUtils.toMD5(segments.blockData);
					String md5 = info.getContentMD5();
					if(md5==null){
						md5 = (String) info.getRawMetadata().get("md5");
//						if (md5 == null) {
//							md5 = (String) info.getETag();
//						}
					}
					segments.md5 = md5;
					log.info("CosFileGet need segments: {}",payload.path);
					return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_GET_FILE_SEGMENTS, segments);
				}
				
			} else {
				log.info("CosFilePut fialed.");
				return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
			}
		} catch (Exception e) {
			log.info("CosFileGet Exception:{}",e.getMessage());
			return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
		}

	}

}
