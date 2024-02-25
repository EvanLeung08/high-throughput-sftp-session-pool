package pool.common.utils;

import java.util.concurrent.ConcurrentHashMap;

public class SftpConnectionBuilder {

    private static volatile SftpConnectionBuilder instance;
    private ConcurrentHashMap<String, SftpSessionPool> sessionPools;
    private int maxSessionsPerHost;
    private int maxChannelsPerSession;

    private SftpConnectionBuilder(int maxSessionsPerHost, int maxChannelsPerSession) {
        this.sessionPools = new ConcurrentHashMap<>();
        this.maxSessionsPerHost = maxSessionsPerHost;
        this.maxChannelsPerSession = maxChannelsPerSession;
    }

    public static SftpConnectionBuilder getInstance(int maxSessionsPerHost, int maxChannelsPerSession) {
        if (instance == null) {
            synchronized (SftpConnectionBuilder.class) {
                if (instance == null) {
                    instance = new SftpConnectionBuilder(maxSessionsPerHost, maxChannelsPerSession);
                }
            }
        }
        return instance;
    }

    public SftpSessionPool getSessionPool(String host, int port, String username) {
        String key = host + ":" + port + ":" + username;
        return sessionPools.computeIfAbsent(key, k -> new SftpSessionPool(maxSessionsPerHost, maxChannelsPerSession));
    }
}