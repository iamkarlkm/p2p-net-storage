package javax.net.p2p.im;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.IMClientUdp;
import javax.net.p2p.im.model.ChatHistoryRequest;
import javax.net.p2p.im.model.ChatMessageAck;
import javax.net.p2p.im.model.GroupDismissRequest;
import javax.net.p2p.im.model.GroupIdRequest;
import javax.net.p2p.im.model.GroupListRequest;
import javax.net.p2p.im.model.GroupMemberRequest;
import javax.net.p2p.im.model.GroupRemoveMemberRequest;
import javax.net.p2p.im.model.GroupSetAdminRequest;
import javax.net.p2p.im.model.GroupUpdateInfoRequest;
import javax.net.p2p.im.runtime.ImRuntime;
import javax.net.p2p.model.IMChatModel;
import javax.net.p2p.model.IMGroupModel;
import javax.net.p2p.model.IMUserModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.IMServerUdp;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import lombok.extern.slf4j.Slf4j;

/**
 * IM功能集成测试
 */
@Slf4j
public class IMIntegrationTest {
    
    private IMServerUdp server;
    private IMClientUdp client;
    private static final int PORT = 10086;
    
    @Before
    public void setUp() throws Exception {
        ImRuntime.clearAll();
        // 启动服务端
        server = IMServerUdp.getInstance(IMServerUdp.class, PORT);
        Thread.sleep(1000); // 等待启动
        
        // 启动客户端
        client = IMClientUdp.getInstance(IMClientUdp.class, new InetSocketAddress("127.0.0.1", PORT));
    }
    
    @After
    public void tearDown() {
        if (client != null) {
            client.released();
        }
        if (server != null) {
            server.released();
        }
        ImRuntime.clearAll();
    }
    
    @Test
    public void testUserLogin() throws Exception {
        IMUserModel user = IMUserModel.builder()
                .userId("user_001")
                .username("test_user")
                .status("ONLINE")
                .build();
        
        P2PWrapper request = P2PWrapper.build(P2PCommand.IM_USER_LOGIN, user);
        P2PWrapper response = client.excute(request, 5, TimeUnit.SECONDS);
        
        Assert.assertNotNull(response);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), response.getCommand().getValue());
        
        IMUserModel responseUser = (IMUserModel) response.getData();
        Assert.assertEquals("user_001", responseUser.getUserId());
        Assert.assertEquals("ONLINE", responseUser.getStatus());
        
        log.info("Login test passed");
    }
    
    @Test
    public void testSendMessage() throws Exception {
        IMUserModel user = IMUserModel.builder()
                .userId("user_001")
                .username("test_user")
                .status("ONLINE")
                .build();
        client.excute(P2PWrapper.build(P2PCommand.IM_USER_LOGIN, user), 5, TimeUnit.SECONDS);

        IMChatModel chatMsg = IMChatModel.builder()
                .senderId("user_001")
                .receiverId("user_002")
                .receiverType("USER")
                .msgType("TEXT")
                .content("Hello P2P IM")
                .build();
        
        P2PWrapper request = P2PWrapper.build(P2PCommand.IM_CHAT_SEND, chatMsg);
        P2PWrapper response = client.excute(request, 5, TimeUnit.SECONDS);
        
        Assert.assertNotNull(response);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), response.getCommand().getValue());
        
        String msgId = (String) response.getData();
        Assert.assertNotNull(msgId);
        
        log.info("Chat test passed, msgId: {}", msgId);
    }

    @Test
    public void testUserListLogoutAndHistory() throws Exception {
        IMUserModel user = IMUserModel.builder()
                .userId("user_001")
                .username("test_user")
                .status("ONLINE")
                .build();
        P2PWrapper loginResp = client.excute(P2PWrapper.build(P2PCommand.IM_USER_LOGIN, user), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(loginResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), loginResp.getCommand().getValue());

        P2PWrapper listResp = client.excute(P2PWrapper.build(P2PCommand.IM_USER_LIST, null), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(listResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), listResp.getCommand().getValue());
        @SuppressWarnings("unchecked")
        java.util.List<IMUserModel> users = (java.util.List<IMUserModel>) listResp.getData();
        Assert.assertTrue(users.stream().anyMatch(u -> "user_001".equals(u.getUserId())));

        IMChatModel chatMsg = IMChatModel.builder()
                .senderId("user_001")
                .receiverId("user_002")
                .receiverType("USER")
                .msgType("TEXT")
                .content("history msg")
                .build();
        P2PWrapper sendResp = client.excute(P2PWrapper.build(P2PCommand.IM_CHAT_SEND, chatMsg), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(sendResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), sendResp.getCommand().getValue());

        ChatHistoryRequest q = new ChatHistoryRequest();
        q.setUserId("user_001");
        q.setPeerId("user_002");
        q.setLimit(10);
        P2PWrapper histResp = client.excute(P2PWrapper.build(P2PCommand.IM_CHAT_HISTORY_REQUEST, q), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(histResp);
        Assert.assertEquals(P2PCommand.IM_CHAT_HISTORY_RESPONSE.getValue(), histResp.getCommand().getValue());
        @SuppressWarnings("unchecked")
        java.util.List<IMChatModel> items = (java.util.List<IMChatModel>) histResp.getData();
        Assert.assertFalse(items.isEmpty());

        P2PWrapper logoutResp = client.excute(P2PWrapper.build(P2PCommand.IM_USER_LOGOUT, user), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(logoutResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), logoutResp.getCommand().getValue());
    }

    @Test
    public void testGroupCreateJoinMembersSendAndHistory() throws Exception {
        IMUserModel user = IMUserModel.builder()
                .userId("user_001")
                .username("test_user")
                .status("ONLINE")
                .build();
        client.excute(P2PWrapper.build(P2PCommand.IM_USER_LOGIN, user), 5, TimeUnit.SECONDS);

        IMGroupModel g = IMGroupModel.builder()
                .name("g1")
                .ownerId("user_001")
                .build();
        P2PWrapper createResp = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_CREATE, g), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(createResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), createResp.getCommand().getValue());
        IMGroupModel created = (IMGroupModel) createResp.getData();
        Assert.assertNotNull(created.getGroupId());

        GroupMemberRequest joinReq = new GroupMemberRequest(created.getGroupId(), "user_002");
        P2PWrapper joinResp = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_JOIN, joinReq), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(joinResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), joinResp.getCommand().getValue());

        GroupSetAdminRequest setAdmin = new GroupSetAdminRequest(created.getGroupId(), "user_001", "user_002", true);
        P2PWrapper setAdminResp = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_SET_ADMIN, setAdmin), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(setAdminResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), setAdminResp.getCommand().getValue());

        P2PWrapper listResp = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_LIST, new GroupListRequest("user_001")), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(listResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), listResp.getCommand().getValue());
        @SuppressWarnings("unchecked")
        java.util.List<IMGroupModel> groups = (java.util.List<IMGroupModel>) listResp.getData();
        Assert.assertTrue(groups.stream().anyMatch(x -> created.getGroupId().equals(x.getGroupId())));

        P2PWrapper listResp2 = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_LIST, new GroupListRequest("user_002")), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(listResp2);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), listResp2.getCommand().getValue());
        @SuppressWarnings("unchecked")
        java.util.List<IMGroupModel> groupsUser2 = (java.util.List<IMGroupModel>) listResp2.getData();
        Assert.assertTrue(groupsUser2.stream().anyMatch(x -> created.getGroupId().equals(x.getGroupId())));

        P2PWrapper listResp3 = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_LIST, new GroupListRequest("user_003")), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(listResp3);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), listResp3.getCommand().getValue());
        @SuppressWarnings("unchecked")
        java.util.List<IMGroupModel> groupsUser3 = (java.util.List<IMGroupModel>) listResp3.getData();
        Assert.assertFalse(groupsUser3.stream().anyMatch(x -> created.getGroupId().equals(x.getGroupId())));

        GroupIdRequest membersReq = new GroupIdRequest(created.getGroupId());
        P2PWrapper membersResp = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_MEMBERS, membersReq), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(membersResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), membersResp.getCommand().getValue());
        IMGroupModel withMembers = (IMGroupModel) membersResp.getData();
        Assert.assertTrue(withMembers.getMembers().contains("user_001"));
        Assert.assertTrue(withMembers.getMembers().contains("user_002"));

        IMChatModel groupMsg = IMChatModel.builder()
                .senderId("user_001")
                .receiverId(created.getGroupId())
                .receiverType("GROUP")
                .msgType("TEXT")
                .content("hello group")
                .build();
        P2PWrapper sendResp = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_MESSAGE_SEND, groupMsg), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(sendResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), sendResp.getCommand().getValue());
        String groupMsgId = (String) sendResp.getData();
        Assert.assertNotNull(groupMsgId);

        ChatMessageAck read = new ChatMessageAck();
        read.setMessageId(groupMsgId);
        read.setAckType("READ");
        read.setUserId("user_002");
        P2PWrapper statusResp = client.excute(P2PWrapper.build(P2PCommand.IM_CHAT_STATUS_UPDATE, read), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(statusResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), statusResp.getCommand().getValue());

        ChatHistoryRequest q = new ChatHistoryRequest();
        q.setUserId("user_001");
        q.setPeerId(created.getGroupId());
        q.setLimit(10);
        P2PWrapper histResp = client.excute(P2PWrapper.build(P2PCommand.IM_CHAT_HISTORY_REQUEST, q), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(histResp);
        Assert.assertEquals(P2PCommand.IM_CHAT_HISTORY_RESPONSE.getValue(), histResp.getCommand().getValue());
        @SuppressWarnings("unchecked")
        java.util.List<IMChatModel> items = (java.util.List<IMChatModel>) histResp.getData();
        Assert.assertFalse(items.isEmpty());
        Assert.assertTrue(items.get(items.size() - 1).getExtra() != null && items.get(items.size() - 1).getExtra().contains("|status:READ"));

        GroupUpdateInfoRequest update = new GroupUpdateInfoRequest(created.getGroupId(), "user_002", "g1_rename", "notice_x", "avatar_x");
        P2PWrapper upResp = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_UPDATE_INFO, update), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(upResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), upResp.getCommand().getValue());
        IMGroupModel updated = (IMGroupModel) upResp.getData();
        Assert.assertEquals("g1_rename", updated.getName());
        Assert.assertEquals("notice_x", updated.getAnnouncement());

        GroupRemoveMemberRequest rm = new GroupRemoveMemberRequest(created.getGroupId(), "user_002", "user_002");
        P2PWrapper rmResp = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_REMOVE_MEMBER, rm), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(rmResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), rmResp.getCommand().getValue());
        P2PWrapper members2 = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_MEMBERS, new GroupIdRequest(created.getGroupId())), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(members2);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), members2.getCommand().getValue());
        IMGroupModel withMembers2 = (IMGroupModel) members2.getData();
        Assert.assertFalse(withMembers2.getMembers().contains("user_002"));

        P2PWrapper listAfterRemove = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_LIST, new GroupListRequest("user_002")), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(listAfterRemove);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), listAfterRemove.getCommand().getValue());
        @SuppressWarnings("unchecked")
        java.util.List<IMGroupModel> groupsAfterRemove = (java.util.List<IMGroupModel>) listAfterRemove.getData();
        Assert.assertFalse(groupsAfterRemove.stream().anyMatch(x -> created.getGroupId().equals(x.getGroupId())));

        GroupDismissRequest disByAdmin = new GroupDismissRequest(created.getGroupId(), "user_002");
        P2PWrapper disAdminResp = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_DISMISS, disByAdmin), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(disAdminResp);
        Assert.assertEquals(P2PCommand.STD_ERROR.getValue(), disAdminResp.getCommand().getValue());

        GroupDismissRequest dis = new GroupDismissRequest(created.getGroupId(), "user_001");
        P2PWrapper disResp = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_DISMISS, dis), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(disResp);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), disResp.getCommand().getValue());
        P2PWrapper list2 = client.excute(P2PWrapper.build(P2PCommand.IM_GROUP_LIST, new GroupListRequest("user_001")), 5, TimeUnit.SECONDS);
        Assert.assertNotNull(list2);
        Assert.assertEquals(P2PCommand.STD_OK.getValue(), list2.getCommand().getValue());
        @SuppressWarnings("unchecked")
        java.util.List<IMGroupModel> groups2 = (java.util.List<IMGroupModel>) list2.getData();
        Assert.assertFalse(groups2.stream().anyMatch(x -> created.getGroupId().equals(x.getGroupId())));
    }
}
