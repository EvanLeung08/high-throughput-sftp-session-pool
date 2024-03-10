package pool.common.utils;

import java.util.concurrent.ConcurrentHashMap;

public class SftpConnectionPoolFactory {

    private static volatile SftpConnectionPoolFactory instance;
    private ConcurrentHashMap<String, SftpSessionPool> sessionPools;


    private SftpConnectionPoolFactory() {
        this.sessionPools = new ConcurrentHashMap<>();
    }

    public static SftpConnectionPoolFactory getInstance() {
        if (instance == null) {
            synchronized (SftpConnectionPoolFactory.class) {
                if (instance == null) {
                    instance = new SftpConnectionPoolFactory();
                }
            }
        }
        return instance;
    }

    public SftpSessionPool getSessionPool(String host, int port, String username, int maxSessionsPerHost, int maxChannelsPerSession) {
        String key = host + ":" + port + ":" + username;
        return sessionPools.computeIfAbsent(key, k -> new SftpSessionPool(maxSessionsPerHost, maxChannelsPerSession));
    }
}