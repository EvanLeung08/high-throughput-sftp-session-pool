package pool.common.utils;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import pool.exceptions.NoAvailableSessionException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SftpSessionPool {
    private ConcurrentHashMap<String, ConcurrentLinkedQueue<Session>> sessions;
    private ConcurrentHashMap<Session, Semaphore> channelCounts;
    private int maxChannelsPerSession;
    private Semaphore maxSessionsPerHostSemaphore;
    ReentrantLockPool lock = new ReentrantLockPool();


    public SftpSessionPool(int maxSessionsPerHost, int maxChannelsPerSession) {
        this.sessions = new ConcurrentHashMap<>();
        this.channelCounts = new ConcurrentHashMap<>();
        this.maxChannelsPerSession = maxChannelsPerSession;
        this.maxSessionsPerHostSemaphore = new Semaphore(maxSessionsPerHost, true);
    }

    public Session getSessionWithKeyLock(String host, int port, String username, String password, long timeout, TimeUnit unit, String lockKey) throws JSchException, InterruptedException {
        String sessionKey = host + ":" + port + ":" + username;
        if (StringUtils.hasLength(lockKey)) {
            // waiting for the available lock
            while (!lock.lock(lockKey)) {
                Thread.sleep(1000); // wait a bit before retrying
                log.debug("Thread {} failed to get lock ", Thread.currentThread().getId());
            }
            log.debug("Thread {} get lock successfully", Thread.currentThread().getId());
        }
        try {
            return getSessionFromPool(host, port, username, password, timeout, unit, sessionKey);
        } finally {
            if (StringUtils.hasLength(lockKey)) {
                lock.unlock(lockKey);
                log.debug("Thread {} release lock successfully", Thread.currentThread().getId());
            }
        }

    }

    public Session getSession(String host, int port, String username, String password, long timeout, TimeUnit unit) throws JSchException, InterruptedException {
        String sessionKey = host + ":" + port + ":" + username;
        return getSessionFromPool(host, port, username, password, timeout, unit, sessionKey);
    }

    private Session getSessionFromPool(String host, int port, String username, String password, long timeout, TimeUnit unit, String sessionKey) throws JSchException, InterruptedException {
        ConcurrentLinkedQueue<Session> hostSessions = sessions.computeIfAbsent(sessionKey, k -> new ConcurrentLinkedQueue<>());
        long endTime = System.nanoTime() + unit.toNanos(timeout);

        while (System.nanoTime() < endTime) {

            for (Session session : hostSessions) {
                if (!session.isConnected()) {
                    // This session is no longer valid, remove it from the pool
                    log.warn("This session is no longer valid, remove it from the pool ,sessionId: {}, session detail: {}", session, sessionKey);
                    hostSessions.remove(session);
                    channelCounts.remove(session);
                    maxSessionsPerHostSemaphore.release();
                    continue;
                }

                Semaphore channelSemaphore = channelCounts.get(session);
                if (channelSemaphore != null && channelSemaphore.tryAcquire()) {
                    return session;
                }
            }

            if (maxSessionsPerHostSemaphore.tryAcquire()) {
                try {
                    Session session = createNewSession(host, port, username, password);
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


    private Session createNewSession(String host, int port, String username, String password) throws JSchException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(username, host, port);
        session.setPassword(password);
        session.setConfig("StrictHostKeyChecking", "no"); // Enable StrictHostKeyChecking
        session.setTimeout(5000); // Set connection timeout to 5 seconds
        session.connect();
        return session;
    }

    public ChannelSftp getChannel(Session session) throws JSchException {
        try {
            return (ChannelSftp) session.openChannel("sftp");
        } catch (JSchException e) {
            if (!session.isConnected()) {
                // The session is no longer valid, remove it from the pool
                String key = session.getHost() + ":" + session.getUserName();
                ConcurrentLinkedQueue<Session> sessions = this.sessions.get(key);
                sessions.remove(session);
                channelCounts.remove(session);
                maxSessionsPerHostSemaphore.release();
            }
            throw e;
        }

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
