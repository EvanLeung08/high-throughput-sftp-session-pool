package pool.common.utils;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import pool.exceptions.NoAvailableSessionException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class SftpSessionPool {
    private ConcurrentHashMap<String, ConcurrentLinkedQueue<Session>> sessions;
    private ConcurrentHashMap<Session, Semaphore> channelCounts;
    private int maxChannelsPerSession;
    private Semaphore maxSessionsPerHostSemaphore;

    public SftpSessionPool(int maxSessionsPerHost, int maxChannelsPerSession) {
        this.sessions = new ConcurrentHashMap<>();
        this.channelCounts = new ConcurrentHashMap<>();
        this.maxChannelsPerSession = maxChannelsPerSession;
        this.maxSessionsPerHostSemaphore = new Semaphore(maxSessionsPerHost, true);
    }

    public synchronized Session getSession(String host, String username, String password, long timeout, TimeUnit unit) throws JSchException, InterruptedException {
        String key = host + ":" + username;
        ConcurrentLinkedQueue<Session> hostSessions = sessions.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        long endTime = System.nanoTime() + unit.toNanos(timeout);

        while (System.nanoTime() < endTime) {
            for (Session session : hostSessions) {
                Semaphore channelSemaphore = channelCounts.get(session);
                if (channelSemaphore != null && channelSemaphore.tryAcquire()) {
                    return session;
                }
            }

            if (maxSessionsPerHostSemaphore.tryAcquire()) {
                try {
                    Session session = createNewSession(host, username, password);
                    hostSessions.add(session);
                    channelCounts.put(session, new Semaphore(maxChannelsPerSession - 1)); // one channel is already in use
                    return session;
                } catch (JSchException e) {
                    maxSessionsPerHostSemaphore.release();
                    throw e;
                }
            }

            Thread.sleep(100); // wait a bit before retrying
        }

        throw new NoAvailableSessionException("Timeout while waiting for a session");
    }

    private Session createNewSession(String host, String username, String password) throws JSchException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(username, host);
        session.setPassword(password);
        session.setConfig("StrictHostKeyChecking", "no"); // Enable StrictHostKeyChecking
        session.setTimeout(5000); // Set connection timeout to 5 seconds
        session.connect();
        return session;
    }

    public synchronized void closeSession(Session session) {
        Semaphore channelSemaphore = channelCounts.get(session);
        if (channelSemaphore != null) {
            channelSemaphore.release();
            if (channelSemaphore.availablePermits() < maxChannelsPerSession) {
                // still channels left, put back to the pool
                return;
            }
        }

        // close the session
        session.disconnect();
        channelCounts.remove(session);
        sessions.values().forEach(queue -> queue.remove(session));
        maxSessionsPerHostSemaphore.release();
    }
}
