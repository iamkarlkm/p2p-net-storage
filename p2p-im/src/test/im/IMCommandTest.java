package javax.net.p2p.im;

import org.junit.Test;
import static org.junit.Assert.*;
import javax.net.p2p.api.P2PCommand;

/**
 * IMCommand测试类
 * 测试即时通讯命令枚举的功能
 */
public class IMCommandTest {

    @Test
    public void testCommandCodes() {
        // 测试命令码存在性
        assertEquals("IM_USER_LOGIN命令码应为10000", 10000, P2PCommand.IM_USER_LOGIN.getValue());
        assertEquals("IM_USER_LOGOUT命令码应为10001", 10001, P2PCommand.IM_USER_LOGOUT.getValue());
        assertEquals("IM_CHAT_SEND命令码应为11000", 11000, P2PCommand.IM_CHAT_SEND.getValue());
        assertEquals("IM_GROUP_CREATE命令码应为12000", 12000, P2PCommand.IM_GROUP_CREATE.getValue());
        assertEquals("IM_SYSTEM_STATUS命令码应为13000", 13000, P2PCommand.IM_SYSTEM_STATUS.getValue());
    }

    @Test
    public void testUserCommands() {
        // 测试用户相关命令
        assertEquals("IM_USER_LOGIN命令码应为10000", 10000, P2PCommand.IM_USER_LOGIN.getValue());
        assertEquals("IM_USER_LOGOUT命令码应为10001", 10001, P2PCommand.IM_USER_LOGOUT.getValue());
        assertEquals("IM_USER_LIST命令码应为10002", 10002, P2PCommand.IM_USER_LIST.getValue());
        assertEquals("IM_USER_HEARTBEAT命令码应为10003", 10003, P2PCommand.IM_USER_HEARTBEAT.getValue());
        assertEquals("IM_USER_STATUS_UPDATE命令码应为10004", 10004, P2PCommand.IM_USER_STATUS_UPDATE.getValue());
    }

    @Test
    public void testChatCommands() {
        // 测试聊天相关命令
        assertEquals("IM_CHAT_SEND命令码应为11000", 11000, P2PCommand.IM_CHAT_SEND.getValue());
        assertEquals("IM_CHAT_RECEIVE命令码应为11001", 11001, P2PCommand.IM_CHAT_RECEIVE.getValue());
        assertEquals("IM_CHAT_ACK命令码应为11002", 11002, P2PCommand.IM_CHAT_ACK.getValue());
        assertEquals("IM_CHAT_STATUS_UPDATE命令码应为11003", 11003, P2PCommand.IM_CHAT_STATUS_UPDATE.getValue());
        assertEquals("IM_CHAT_HISTORY_REQUEST命令码应为11004", 11004, P2PCommand.IM_CHAT_HISTORY_REQUEST.getValue());
        assertEquals("IM_CHAT_HISTORY_RESPONSE命令码应为11005", 11005, P2PCommand.IM_CHAT_HISTORY_RESPONSE.getValue());
        assertEquals("IM_CHAT_RECALL命令码应为11006", 11006, P2PCommand.IM_CHAT_RECALL.getValue());
        assertEquals("IM_CHAT_FORWARD命令码应为11007", 11007, P2PCommand.IM_CHAT_FORWARD.getValue());
    }

    @Test
    public void testGroupCommands() {
        // 测试群组相关命令
        assertEquals("IM_GROUP_CREATE命令码应为12000", 12000, P2PCommand.IM_GROUP_CREATE.getValue());
        assertEquals("IM_GROUP_DISMISS命令码应为12001", 12001, P2PCommand.IM_GROUP_DISMISS.getValue());
        assertEquals("IM_GROUP_JOIN命令码应为12002", 12002, P2PCommand.IM_GROUP_JOIN.getValue());
        assertEquals("IM_GROUP_LEAVE命令码应为12003", 12003, P2PCommand.IM_GROUP_LEAVE.getValue());
        assertEquals("IM_GROUP_LIST命令码应为12004", 12004, P2PCommand.IM_GROUP_LIST.getValue());
        assertEquals("IM_GROUP_MEMBERS命令码应为12005", 12005, P2PCommand.IM_GROUP_MEMBERS.getValue());
        assertEquals("IM_GROUP_MESSAGE_SEND命令码应为12006", 12006, P2PCommand.IM_GROUP_MESSAGE_SEND.getValue());
        assertEquals("IM_GROUP_MESSAGE_RECEIVE命令码应为12007", 12007, P2PCommand.IM_GROUP_MESSAGE_RECEIVE.getValue());
        assertEquals("IM_GROUP_SET_ADMIN命令码应为12008", 12008, P2PCommand.IM_GROUP_SET_ADMIN.getValue());
        assertEquals("IM_GROUP_REMOVE_MEMBER命令码应为12009", 12009, P2PCommand.IM_GROUP_REMOVE_MEMBER.getValue());
        assertEquals("IM_GROUP_UPDATE_INFO命令码应为12010", 12010, P2PCommand.IM_GROUP_UPDATE_INFO.getValue());
    }

    @Test
    public void testSystemCommands() {
        // 测试系统相关命令
        assertEquals("IM_SYSTEM_STATUS命令码应为13000", 13000, P2PCommand.IM_SYSTEM_STATUS.getValue());
        assertEquals("IM_CONNECTION_TEST命令码应为13001", 13001, P2PCommand.IM_CONNECTION_TEST.getValue());
        assertEquals("IM_ERROR_RESPONSE命令码应为13002", 13002, P2PCommand.IM_ERROR_RESPONSE.getValue());
    }

    @Test
    public void testCommandCount() {
        // 测试命令数量
        P2PCommand[] allCommands = P2PCommand.values();

        // 计算IM命令数量（以IM_开头的）
        int imCommandCount = 0;
        for (P2PCommand command : allCommands) {
            if (command.name().startsWith("IM_")) {
                imCommandCount++;
            }
        }

        assertEquals("IM命令总数应为27个", 27, imCommandCount);
    }

    @Test
    public void testCommandNaming() {
        // 测试命令命名规范
        assertTrue("用户命令应以IM_USER_开头", P2PCommand.IM_USER_LOGIN.name().startsWith("IM_USER_"));
        assertTrue("聊天命令应以IM_CHAT_开头", P2PCommand.IM_CHAT_SEND.name().startsWith("IM_CHAT_"));
        assertTrue("群组命令应以IM_GROUP_开头", P2PCommand.IM_GROUP_CREATE.name().startsWith("IM_GROUP_"));
        assertTrue("系统命令应以IM_SYSTEM_开头", P2PCommand.IM_SYSTEM_STATUS.name().startsWith("IM_SYSTEM_"));
    }

    @Test
    public void testCommandRanges() {
        // 测试命令码范围
        // 用户命令: 10000-10004
        assertTrue("IM_USER_LOGIN应在用户命令范围", P2PCommand.IM_USER_LOGIN.getValue() >= 10000 && P2PCommand.IM_USER_LOGIN.getValue() <= 10004);
        assertTrue("IM_USER_STATUS_UPDATE应在用户命令范围", P2PCommand.IM_USER_STATUS_UPDATE.getValue() >= 10000 && P2PCommand.IM_USER_STATUS_UPDATE.getValue() <= 10004);

        // 聊天命令: 11000-11007
        assertTrue("IM_CHAT_SEND应在聊天命令范围", P2PCommand.IM_CHAT_SEND.getValue() >= 11000 && P2PCommand.IM_CHAT_SEND.getValue() <= 11007);
        assertTrue("IM_CHAT_FORWARD应在聊天命令范围", P2PCommand.IM_CHAT_FORWARD.getValue() >= 11000 && P2PCommand.IM_CHAT_FORWARD.getValue() <= 11007);

        // 群组命令: 12000-12010
        assertTrue("IM_GROUP_CREATE应在群组命令范围", P2PCommand.IM_GROUP_CREATE.getValue() >= 12000 && P2PCommand.IM_GROUP_CREATE.getValue() <= 12010);
        assertTrue("IM_GROUP_UPDATE_INFO应在群组命令范围", P2PCommand.IM_GROUP_UPDATE_INFO.getValue() >= 12000 && P2PCommand.IM_GROUP_UPDATE_INFO.getValue() <= 12010);

        // 系统命令: 13000-13002
        assertTrue("IM_SYSTEM_STATUS应在系统命令范围", P2PCommand.IM_SYSTEM_STATUS.getValue() >= 13000 && P2PCommand.IM_SYSTEM_STATUS.getValue() <= 13002);
        assertTrue("IM_ERROR_RESPONSE应在系统命令范围", P2PCommand.IM_ERROR_RESPONSE.getValue() >= 13000 && P2PCommand.IM_ERROR_RESPONSE.getValue() <= 13002);
    }
}
