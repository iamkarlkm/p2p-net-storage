package javax.net.p2p.im;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.IMClientUdp;
import javax.net.p2p.model.IMChatModel;
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
}
