import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.util.Map;
import java.util.Vector;
import java.util.concurrent.*;

class SftpConnectionPool {
    private ConcurrentHashMap<String, ConcurrentLinkedQueue<Session>> sessions;
    private ConcurrentHashMap<Session, Semaphore> channelCounts;
    private int maxChannelsPerSession;
    private Semaphore maxSessionsPerHostSemaphore;

    public SftpConnectionPool(int maxSessionsPerHost, int maxChannelsPerSession) {
        this.sessions = new ConcurrentHashMap<>();
        this.channelCounts = new ConcurrentHashMap<>();
        this.maxChannelsPerSession = maxChannelsPerSession;
        this.maxSessionsPerHostSemaphore = new Semaphore(maxSessionsPerHost, true);
    }

    public synchronized Session getSession(String host, String username, String password) throws JSchException, InterruptedException {
        String key = host + ":" + username;
        ConcurrentLinkedQueue<Session> hostSessions = sessions.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());

        while (true) {
            for (Session session : hostSessions) {
                Semaphore channelSemaphore = channelCounts.get(session);
                if (channelSemaphore != null && channelSemaphore.tryAcquire()) {
                    return session;
                }
            }

            if (maxSessionsPerHostSemaphore.tryAcquire()) {
                Session session = createNewSession(host, username, password);
                hostSessions.add(session);
                channelCounts.put(session, new Semaphore(maxChannelsPerSession - 1)); // one channel is already in use
                return session;
            }

            Thread.sleep(100); // wait a bit before retrying
        }
    }

    private Session createNewSession(String host, String username, String password) throws JSchException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(username, host);
        session.setPassword(password);
        //jsch.addIdentity(privateKeyPath); // Use private key for authentication
        session.setConfig("StrictHostKeyChecking", "no"); // Enable StrictHostKeyChecking
        session.setTimeout(5000); // Set connection timeout to 5 seconds
        session.connect();
        return session;
    }

    public void closeSession(Session session) {
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

class TestThread extends Thread {
    private SftpConnectionPool pool;
    private String host;
    private String username;
    private String password;

    public TestThread(SftpConnectionPool pool, String host, String username, String password) {
        this.pool = pool;
        this.host = host;
        this.username = username;
        this.password = password;
    }

    public void run() {
        try {
            Session session = pool.getSession(host, username, password);
            System.out.println("Thread " + this.getId() + " got session: " + session);

            ChannelSftp channelSftp = (ChannelSftp) session.openChannel("sftp");
            channelSftp.connect();
            // ... use the session and channel
            Vector<ChannelSftp.LsEntry> list = channelSftp.ls("/tmp/evan");
            for (ChannelSftp.LsEntry entry : list) {
                // System.out.println(entry.getFilename());
                Thread.sleep(1000);
            }
            channelSftp.disconnect();

            pool.closeSession(session);
            System.out.println("Thread " + this.getId() + " closed session: " + session);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

public class Main {
    final static Map<String, SftpConnectionPool> map = new ConcurrentHashMap();

    public static void main(String[] args) {

        map.put("192.168.50.33" + "parallels", new SftpConnectionPool(3, 1));
        map.put("192.168.50.57" + "parallels", new SftpConnectionPool(3, 1));
        for (int i = 0; i < 3; i++) {
            TestThread thread = new TestThread(map.get("192.168.50.33" + "parallels"), "192.168.50.33", "parallels", "xxx");
            thread.start();
            TestThread thread1 = new TestThread(map.get("192.168.50.57" + "parallels"), "192.168.50.57", "parallels", "xxx");
            thread1.start();
        }

    }
}
