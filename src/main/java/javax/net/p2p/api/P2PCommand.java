package javax.net.p2p.api;

/**
 * 自定义P2P协议命令
 *
 */
public enum P2PCommand {
	
	/**
     * no op空操作
     */
    NOOP(0),
	
	/**
     * 心跳检测
     */
    HEART_PING(1010),
    /**
     * 心跳检测
     */
    HEART_PONG(10101),
    /**
     * 登录指令
     */
    LOGIN(2),
    /**
     * 登出指令
     */
    LOGOUT(3),
    /**
     * 握手消息
     */
    HAND(4),
	/**
     * 文本消息
     */
    ECHO(1001),
    /**
     * 查询节点信息
     */
    //GET_NODE(5),
    /**
     * 标准回应信息
     */
    STD_OK(6),
    STD_ERROR(-1),STD_CANCEL(-1101), STD_STOP(-102), QUIC_FIN(-103), QUIC_SHUTDOWN(-104),
	//无效数据包
	INVALID_DATA(-2),
    //无效协议标识
	INVALID_PROTOCOL(-7),
	//对象已存在
	EXISTS(-3),
	REMOVE_FILE(-4),
    GET_FILE(7),
    PUT_FILE(14),FORCE_PUT_FILE(15),
	R_OK_GET_FILE(8), GET_HDFS_FILE(9), R_OK_GET_HDFS_FILE(10), GET_HDFS_FILE_INFO(11), R_OK_GET_HDFS_FILE_INFO(12), PUT_HDFS_FILE(13),
        HDFS_COMMAND(16), R_OK_PUT_HDFS_FILE(17), PUT_HDFS_BLOCK(18), GET_HDFS_BLOCK(25),
         FILES_COMMAND(19),GET_FILE_SEGMENTS(20),PUT_FILE_SEGMENTS(21),CHECK_FILE(22),CHECK_HDFS_FILE(23),
		 GET_COS_FILE(26), R_OK_GET_COS_FILE(27),COS_COMMAND(28), PUT_COS_FILE(29), PUT_COS_FILE_FROM_HDFS(30)
		 ,CHECK_COS_FILE(24),GET_LSSJ_FILE(40),CHECK_LSSJ_FILE(41),R_OK_GET_FILE_SEGMENTS(43)
		 ,PUT_FILE_SEGMENTS_COMPLETE(44), PUT_COS_FILE_SEGMENTS(45),INFO_FILE(46),GET_COS_FILE_SEGMENTS(47)
		 , PUT_COS_FILE_SEGMENTS_COMPLETE(48), SET_FILE_SEGMENTS_GET_BLOCK_SIZE(49), SET_FILE_SEGMENTS_PUT_BLOCK_SIZE(49),
                 //流操作
                 STREAM_ACK(100),GET_FILE_STREAM(101),R_OK_GET_FILE_STREAM(102),PUT_FILE_STREAM(101),R_OK_PUT_FILE_STREAM(102)
                 ,GET_FILE_REGION(110),R_OK_GET_FILE_REGION(111)
                 //UDP流操作
                 ,UDP_FRAME_ACK(200),UDP_FRAME_RESET(201)
                 ,UDP_STREAM_ACK2(209);
    private final int value;

    P2PCommand(int val) {
        this.value = val;
    }

    public int getValue() {
        return value;
    }

}
