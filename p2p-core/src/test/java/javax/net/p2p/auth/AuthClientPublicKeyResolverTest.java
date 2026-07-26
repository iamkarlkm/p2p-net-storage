package javax.net.p2p.auth;

import javax.net.p2p.auth.config.AuthConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class AuthClientPublicKeyResolverTest {

    @Test
    public void resolvesLegacyMapFirst() {
        AuthConfig.Server server = new AuthConfig.Server();
        server.getClientPublicKeys().put("u1", "client-public.key");
        server.setClientPublicKeyTemplate("client-public-keys/{userId}.pub");

        assertEquals("client-public.key", AuthClientPublicKeyResolver.resolve(server, "u1"));
    }

    @Test
    public void resolvesTemplate() {
        AuthConfig.Server server = new AuthConfig.Server();
        server.setClientPublicKeyTemplate("client-public-keys/{userIdPrefix2}/{userId}.pub");

        assertEquals("client-public-keys/ab/abcdef.pub", AuthClientPublicKeyResolver.resolve(server, "abcdef"));
    }

    @Test
    public void rejectsUnsafeUserId() {
        AuthConfig.Server server = new AuthConfig.Server();
        server.setClientPublicKeyTemplate("client-public-keys/{userId}.pub");
        assertNull(AuthClientPublicKeyResolver.resolve(server, "../x"));
        assertNull(AuthClientPublicKeyResolver.resolve(server, "a/b"));
    }
}

