package com.q3lives.ds.database.config;

import java.util.List;
import javax.net.p2p.auth.config.AuthConfig;

public class DsDatabaseClientConfig {
    public String mode;
    public Local local;
    public Server server;
    public MetaCheck metaCheck;
    
    public static class Local {
        public String dbHome;
    }
    
    public static class Server {
        public String ip;
        public int port;
        public AuthConfig auth;
    }

    public static class MetaCheck {
        public boolean enabled;
        public boolean strict = true;
        public boolean ensureFresh;
        public boolean requireCache;
        public List<String> entityClasses;
        public List<String> entityPackages;
    }
}
