package javax.net.p2p.auth;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.error.P2PStdError;
import javax.net.p2p.model.P2PWrapper;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class AuthEnforcerRoleCategoryTest {

    @After
    public void tearDown() {
        System.clearProperty("p2p.auth.inlineYaml");
        System.clearProperty("p2p.auth.inlineBaseDir");
        System.clearProperty("p2p.auth.yaml");
    }

    @Test
    public void roleAllowsByCategoryAndCommand() {
        String yaml = ""
                + "enabled: true\n"
                + "xorKeyLength: 4096\n"
                + "server:\n"
                + "  roles:\n"
                + "    core_compat:\n"
                + "      allowCommands:\n"
                + "        - ECHO\n"
                + "      allowCategories:\n"
                + "        - RPC\n"
                + "    dsdb_admin:\n"
                + "      allowCategories:\n"
                + "        - DS_DB\n"
                + "  defaultRoles:\n"
                + "    - core_compat\n"
                + "  roleBindings:\n"
                + "    - match: \"admin-*\"\n"
                + "      roles:\n"
                + "        - dsdb_admin\n";
        applyInlineYaml(yaml);

        EmbeddedChannel ch = authedChannel("user-1");
        assertNull(AuthEnforcer.check(ch, P2PWrapper.build(P2PCommand.RPC_DISCOVER, null)));
        assertNull(AuthEnforcer.check(ch, P2PWrapper.build(P2PCommand.ECHO, "hello")));
        assertDenied(AuthEnforcer.check(ch, P2PWrapper.build(P2PCommand.DB_META_GET, null)));

        EmbeddedChannel admin = authedChannel("admin-1");
        assertNull(AuthEnforcer.check(admin, P2PWrapper.build(P2PCommand.DB_META_GET, null)));
    }

    @Test
    public void legacyAllowCommandsStillWorks() {
        String yaml = ""
                + "enabled: true\n"
                + "xorKeyLength: 4096\n"
                + "server:\n"
                + "  allowCommands:\n"
                + "    user-1:\n"
                + "      - ECHO\n";
        applyInlineYaml(yaml);

        EmbeddedChannel ch = authedChannel("user-1");
        assertNull(AuthEnforcer.check(ch, P2PWrapper.build(P2PCommand.ECHO, "hello")));
        assertDenied(AuthEnforcer.check(ch, P2PWrapper.build(P2PCommand.RPC_DISCOVER, null)));
    }

    @Test
    public void roleAuthDefaultsToDenyWhenNoRoleResolved() {
        String yaml = ""
                + "enabled: true\n"
                + "xorKeyLength: 4096\n"
                + "server:\n"
                + "  roles:\n"
                + "    core_compat:\n"
                + "      allowCategories:\n"
                + "        - RPC\n";
        applyInlineYaml(yaml);

        EmbeddedChannel ch = authedChannel("user-1");
        assertDenied(AuthEnforcer.check(ch, P2PWrapper.build(P2PCommand.RPC_DISCOVER, null)));
    }

    private static void applyInlineYaml(String yaml) {
        System.setProperty("p2p.auth.inlineYaml", yaml);
        System.setProperty("p2p.auth.yaml", "inline-" + UUID.randomUUID());
    }

    private static EmbeddedChannel authedChannel(String userId) {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.attr(ChannelUtils.XOR_KEY).set(new byte[]{1});
        ch.attr(ChannelUtils.AUTH_LOGGED_IN).set(true);
        ch.attr(ChannelUtils.AUTH_USER_ID).set(userId);
        return ch;
    }

    private static void assertDenied(P2PWrapper wrapper) {
        assertNotNull(wrapper);
        assertEquals(P2PCommand.STD_ERROR, wrapper.getCommand());
        P2PStdError err = P2PErrors.asStdError(wrapper.getData());
        assertEquals(P2PErrorCode.AUTH_PERMISSION_DENIED.key(), err.getKey());
    }
}

