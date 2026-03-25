package javax.net.p2p.api;

/**
 * P2PCommand - P2P协议命令枚举，定义所有网络通信命令
 * 
 * 功能说明：
 * 1. 定义P2P协议的所有命令类型和对应的数值编码
 * 2. 提供命令的序列化和反序列化支持
 * 3. 分类管理不同类型的命令（握手、文件操作、云存储等）
 * 
 * 命令分类：
 * 1. 基础控制命令：NOOP, HEART_PING, HEART_PONG, LOGIN, LOGOUT
 * 2. 握手命令：HAND, STD_OK, STD_ERROR
 * 3. 文件操作命令：GET_FILE, PUT_FILE, GET_FILE_SEGMENTS, PUT_FILE_SEGMENTS
 * 4. 云存储命令：GET_COS_FILE, PUT_COS_FILE, GET_COS_FILE_SEGMENTS
 * 5. HDFS操作命令：GET_HDFS_FILE, PUT_HDFS_FILE, GET_HDFS_BLOCK
 * 6. 响应命令：R_OK_GET_FILE, R_OK_GET_FILE_SEGMENTS, R_OK_PUT_FILE
 * 7. 流操作命令：STREAM_ACK, GET_FILE_STREAM, PUT_FILE_STREAM
 * 8. UDP协议命令：UDP_FRAME_ACK, UDP_FRAME_RESET, UDP_STREAM_ACK2
 * 
 * 编码规则：
 * - 正值：请求命令或正常操作命令
 * - 负值：错误响应或特殊控制命令
 * - 特殊范围：1000+为心跳相关，100+为流操作，200+为UDP操作
 * 
 * 使用场景：
 * - 网络消息编解码时确定消息类型
 * - 服务器路由消息到对应的处理器
 * - 客户端发送不同类型的请求
 * 
 * @version 1.0
 * @since 2025
 */
public enum P2PCommand {
	
    // ============== 基础控制命令 ==============
    
    /** 空操作，用于测试或占位 */
    NOOP(0),
    
    /** 心跳检测请求，客户端向服务器发送 */
    HEART_PING(1010),
    
    /** 心跳检测响应，服务器回应客户端 */
    HEART_PONG(10101),
    
    /** 用户登录命令，建立用户会话 */
    LOGIN(2),
    
    /** 用户登出命令，结束用户会话 */
    LOGOUT(3),
    
    /** 握手消息，建立连接时的初始问候 */
    HAND(4),
    
    /** 文本消息，用于传输简单文本数据 */
    ECHO(1001),
    
    // ============== 即时通讯命令 ==============
    
    /** 获取在线用户列表 */
    GET_ONLINE_USERS(1002),
    
    /** 点对点聊天消息 */
    CHAT_MESSAGE(1003),
    
    /** 创建群组 */
    CREATE_GROUP(1004),
    
    /** 加入群组 */
    JOIN_GROUP(1005),
    
    /** 离开群组 */
    LEAVE_GROUP(1006),
    
    /** 群聊消息 */
    GROUP_MESSAGE(1007),
    
    /** 发送文件 */
    SEND_FILE(1008),
    
    /** 通知消息（用户上线/下线等） */
    NOTIFICATION(1009),
    
    // ============== 标准响应命令 ==============
    
    /** 标准成功响应，表示操作成功完成 */
    STD_OK(6),
    
    /** 标准错误响应，表示操作失败 */
    STD_ERROR(-1),
    
    /** 操作取消响应 */
    STD_CANCEL(-1101),
    
    /** 停止操作响应 */
    STD_STOP(-102),
    
    /** QUIC协议结束标志 */
    QUIC_FIN(-103),
    
    /** QUIC协议关闭标志 */
    QUIC_SHUTDOWN(-104),
    
    // ============== 错误状态命令 ==============
    
    /** 无效数据包错误 */
    INVALID_DATA(-2),
    
    /** 无效协议标识错误 */
    INVALID_PROTOCOL(-7),
    
    /** 对象已存在错误 */
    EXISTS(-3),
    
    /** 删除文件命令 */
    REMOVE_FILE(-4),
    
    // ============== 本地文件操作命令 ==============
    
    /** 获取文件命令，从服务器读取文件 */
    GET_FILE(7),
    
    /** 成功获取文件响应 */
    R_OK_GET_FILE(8),
    
    /** 上传文件命令，向服务器写入文件 */
    PUT_FILE(14),
    
    /** 强制上传文件命令，覆盖已存在的文件 */
    FORCE_PUT_FILE(15),
    
    /** 获取文件分片命令，支持大文件分片传输 */
    GET_FILE_SEGMENTS(20),
    
    /** 成功获取文件分片响应 */
    R_OK_GET_FILE_SEGMENTS(43),
    
    /** 上传文件分片命令 */
    PUT_FILE_SEGMENTS(21),
    
    /** 文件分片上传完成确认 */
    PUT_FILE_SEGMENTS_COMPLETE(44),
    
    /** 检查文件命令，验证文件是否存在和完整性 */
    CHECK_FILE(22),
    
    /** 文件信息查询命令 */
    INFO_FILE(46),
    
    /** 设置文件获取分块大小 */
    SET_FILE_SEGMENTS_GET_BLOCK_SIZE(49),
    
    /** 设置文件上传分块大小 */
    SET_FILE_SEGMENTS_PUT_BLOCK_SIZE(49),
    
    // ============== HDFS文件操作命令 ==============
    
    /** 获取HDFS文件命令 */
    GET_HDFS_FILE(9),
    
    /** 成功获取HDFS文件响应 */
    R_OK_GET_HDFS_FILE(10),
    
    /** 获取HDFS文件信息命令 */
    GET_HDFS_FILE_INFO(11),
    
    /** 成功获取HDFS文件信息响应 */
    R_OK_GET_HDFS_FILE_INFO(12),
    
    /** 上传HDFS文件命令 */
    PUT_HDFS_FILE(13),
    
    /** 成功上传HDFS文件响应 */
    R_OK_PUT_HDFS_FILE(17),
    
    /** HDFS通用命令 */
    HDFS_COMMAND(16),
    
    /** 上传HDFS数据块命令 */
    PUT_HDFS_BLOCK(18),
    
    /** 获取HDFS数据块命令 */
    GET_HDFS_BLOCK(25),
    
    /** 检查HDFS文件命令 */
    CHECK_HDFS_FILE(23),
    
    /** 文件操作通用命令 */
    FILES_COMMAND(19),
    
    // ============== 腾讯云COS操作命令 ==============
    
    /** 获取腾讯云COS文件命令 */
    GET_COS_FILE(26),
    
    /** 成功获取腾讯云COS文件响应 */
    R_OK_GET_COS_FILE(27),
    
    /** 获取腾讯云COS文件分片命令 */
    GET_COS_FILE_SEGMENTS(47),
    
    /** 上传腾讯云COS文件命令 */
    PUT_COS_FILE(29),
    
    /** 上传腾讯云COS文件分片命令 */
    PUT_COS_FILE_SEGMENTS(45),
    
    /** 腾讯云COS文件分片上传完成确认 */
    PUT_COS_FILE_SEGMENTS_COMPLETE(48),
    
    /** 从HDFS上传到腾讯云COS命令 */
    PUT_COS_FILE_FROM_HDFS(30),
    
    /** 检查腾讯云COS文件命令 */
    CHECK_COS_FILE(24),
    
    /** 腾讯云COS通用命令 */
    COS_COMMAND(28),
    
    // ============== 流式文件操作命令 ==============
    
    /** 流操作确认 */
    STREAM_ACK(100),
    
    /** 获取文件流命令 */
    GET_FILE_STREAM(101),
    
    /** 成功获取文件流响应 */
    R_OK_GET_FILE_STREAM(102),
    
    /** 上传文件流命令 */
    PUT_FILE_STREAM(101),
    
    /** 成功上传文件流响应 */
    R_OK_PUT_FILE_STREAM(102),
    
    /** 获取文件区域命令 */
    GET_FILE_REGION(110),
    
    /** 成功获取文件区域响应 */
    R_OK_GET_FILE_REGION(111),
    
    // ============== UDP协议操作命令 ==============
    
    /** UDP帧确认 */
    UDP_FRAME_ACK(200),
    
    /** UDP帧重置 */
    UDP_FRAME_RESET(201),
    
    /** UDP流操作确认2 */
    UDP_STREAM_ACK2(209),
    
    // ============== 遗留系统命令 ==============

    /** 获取LSSJ系统文件命令（遗留系统） */
    GET_LSSJ_FILE(40),

    /** 检查LSSJ系统文件命令（遗留系统） */
    CHECK_LSSJ_FILE(41),

    // ============== 即时通讯系统命令 (10000-13099) ==============

    // ========== 用户相关命令 (10000-10099) ==========

    /** IM_用户登录 */
    IM_USER_LOGIN(10000),

    /** IM_用户登出 */
    IM_USER_LOGOUT(10001),

    /** IM_获取在线用户列表 */
    IM_USER_LIST(10002),

    /** IM_用户心跳 */
    IM_USER_HEARTBEAT(10003),

    /** IM_用户状态更新 */
    IM_USER_STATUS_UPDATE(10004),

    // ========== 聊天相关命令 (11000-11099) ==========

    /** IM_发送聊天消息 */
    IM_CHAT_SEND(11000),

    /** IM_接收聊天消息 */
    IM_CHAT_RECEIVE(11001),

    /** IM_消息确认 */
    IM_CHAT_ACK(11002),

    /** IM_消息状态更新 */
    IM_CHAT_STATUS_UPDATE(11003),

    /** IM_历史消息请求 */
    IM_CHAT_HISTORY_REQUEST(11004),

    /** IM_历史消息响应 */
    IM_CHAT_HISTORY_RESPONSE(11005),

    /** IM_消息撤回 */
    IM_CHAT_RECALL(11006),

    /** IM_消息转发 */
    IM_CHAT_FORWARD(11007),

    // ========== 群组相关命令 (12000-12099) ==========

    /** IM_创建群组 */
    IM_GROUP_CREATE(12000),

    /** IM_解散群组 */
    IM_GROUP_DISMISS(12001),

    /** IM_加入群组 */
    IM_GROUP_JOIN(12002),

    /** IM_离开群组 */
    IM_GROUP_LEAVE(12003),

    /** IM_获取群组列表 */
    IM_GROUP_LIST(12004),

    /** IM_获取群组成员 */
    IM_GROUP_MEMBERS(12005),

    /** IM_群组消息发送 */
    IM_GROUP_MESSAGE_SEND(12006),

    /** IM_群组消息接收 */
    IM_GROUP_MESSAGE_RECEIVE(12007),

    /** IM_设置群组管理员 */
    IM_GROUP_SET_ADMIN(12008),

    /** IM_移除群组成员 */
    IM_GROUP_REMOVE_MEMBER(12009),

    /** IM_更新群组信息 */
    IM_GROUP_UPDATE_INFO(12010),

    // ========== 系统状态命令 (13000-13099) ==========

    /** IM_系统状态查询 */
    IM_SYSTEM_STATUS(13000),

    /** IM_连接测试 */
    IM_CONNECTION_TEST(13001),

    /** IM_错误响应 */
    IM_ERROR_RESPONSE(13002), DATA_TRANSFER(21000);
    /** 命令的数值编码，用于网络传输和序列化 */
    private final int value;

    /**
     * 构造函数，初始化命令的数值编码
     * 
     * 编码规则说明：
     * - 正值（1-999）：常规操作命令
     * - 负值（-1以下）：错误或特殊控制命令  
     * - 1000+：心跳相关命令
     * - 100+：流操作命令
     * - 200+：UDP协议命令
     * 
     * @param val 命令的数值编码
     */
    P2PCommand(int val) {
        this.value = val;
    }

    /**
     * 获取命令的数值编码
     * 
     * 使用场景：
     * - 网络消息编码时获取命令的整数值
     * - 序列化时将枚举转换为整数
     * - 日志记录时显示命令的编码
     * 
     * @return 命令的数值编码
     */
    public int getValue() {
        return value;
    }

}
