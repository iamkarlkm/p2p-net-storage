package com.q3lives.ds.database.config;

import javax.net.p2p.auth.config.AuthConfig;

public class DsDatabaseClientConfig {
    public String mode;
    public Local local;
    public Server server;
    
    public static class Local {
        public String dbHome;
    }
    
    public static class Server {
        public String ip;
        public int port;
        public AuthConfig auth;
    }
}

