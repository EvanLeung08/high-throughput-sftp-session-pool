# Background

In the modern data-driven environment, the Secure File Transfer Protocol (SFTP) plays a crucial role by providing a secure and reliable file transfer method. Our current project is a large-scale data integration platform, where many file interfaces are connected via the SFTP protocol. When dealing with large volumes of files or large-scale data migration, we often encounter connection access interruptions or file transfer interruptions due to limitations on the upstream and downstream servers, significantly affecting the system's file transfer efficiency. Common error examples include ``com.jcraft.jsch.JSchException: Session.connect: java.net.SocketException: Connection reset, com.jcraft.jsch.JSchException: connection is closed by foreign host, com.jcraft.jsch.JSchException: session is down, com.jcraft.jsch.JSchException: channel is not opened``, etc. These connection issues have also been a long-standing concern for us. So, is there a way to maximize file parallel processing capabilities with limited resources on the upstream and downstream servers? The current connection pool solutions on the internet only solve the reuse of ``SSH Session`` but do not fully utilize the ``SFTP Channel`` within the same ``SSH Session`` to reduce the overhead of ``Session``, so when the file volume is large, the efficiency of file parallel transmission is still affected by the server's ``SSH Session`` quantity limit. The most resource-saving and effective method to increase file parallel processing capabilities is to reuse both ``SSH Session`` and its ``SFTP Channel``. This way, the maximum number of files that can be processed in parallel is the product of ``SSH Session count *SFTP Channel count``, rather than each file processing thread opening a ``SSH Session`` and using only one ``Channel`` of ``SSH Session``.

# Problem Cause
All upstream and downstream SFTP servers have connection limits. Generally, the default ``MaxSessions`` for common Linux servers is ``10``, and ``MaxStartups`` is ``10:30:60``.

Here's a brief explanation of the two configuration parameters ``MaxStartups`` and ``MaxSessions``, which is important for understanding SFTP:
- ``MaxStartups 10:30:60``: This indicates that the maximum number of unauthenticated connections the server can handle at startup is 10. Once the number of connections exceeds the first number (**10**), ``SSHD`` will start to randomly reject new connections. When the number of connections reaches the third number (**60**), ``SSHD`` will reject all new connections. Note that this is for unauthenticated connections; already logged-in connections are not limited. In other words, if the client creates multiple connections waiting for authentication, the number exceeding this configuration will be rejected.
- ``MaxSessions 10``: This specifies the maximum number of communication channels that can be created per ``SSH`` connection, such as the ``ChannelSftp`` file processing channel in ``JSCH``.

If the upstream and downstream do not set their SFTP servers, the server will allow a maximum of **10** simultaneous ``SSH`` connections (exceeding **10** connections will cause the ``SSHD`` service on the server to randomly interrupt connections, leading to unstable service start). Each ``SSH`` connection can open up to **10** ``SFTP Channels``.

> The MaxSessions and MaxStartups parameters in the SSH daemon (SSHD) configuration significantly impact Java SFTP clients, especially when using the JSch library. Here's how these parameters affect the Java SFTP client:
> - ``MaxStartups``: This parameter controls the maximum number of concurrent unauthenticated connections to the SSH daemon. It's crucial for preventing brute-force attacks by limiting the number of connections attempting authentication simultaneously. For ``JSch`` clients, this means that if you attempt to concurrently create multiple Session objects (representing multiple ``SSH`` connections), the server might start rejecting additional connections once the limit is reached. This setting is particularly relevant when dealing with high concurrency scenarios, such as when a large number of SFTP transfers are initiated simultaneously.
> - ``MaxSessions``: This parameter specifies the maximum number of open sessions (e.g., shell, login, or subsystem sessions like SFTP) permitted per network connection. It affects how many ``ChannelSftp`` objects (or other types of channels) can be created within a ``single Session`` object in ``JSch``. If you try to create more channels than the ``MaxSessions`` limit allows, the server might reject new channel creation requests. This setting is essential for managing the number of parallel SFTP operations that can be performed over a single ``SSH`` connection.

In summary, both ``MaxStartups`` and ``MaxSessions`` are server-side limitations that affect how many Session and Channel objects a client (e.g., an application using ``JSch``) can concurrently create. When designing a system that needs to handle a large number of concurrent SFTP transfers, it's important to consider these limits and adjust the ``SSHD`` configuration on the server as necessary to accommodate the application's requirements.

**As an example, let's use a real-world model to briefly describe the current scenario of downloading files on our data integration platform**

Our data integration platform needs to download files from different upstream sources, with `"A, B, C"` companies serving as our upstream systems, and "Delivery Company" representing our data integration system. As shown in the diagram, from the perspective of one courier company, Delivery Company needs to pick up packages from multiple merchants. To increase the speed of receiving packages, the ideal solution would be to have different employees pick up packages from different merchants in parallel. This way, packages can be received simultaneously at the fastest possible speed. From the perspective of one merchant, A Company needs to deal with parallel pickup requests from different courier companies. This inevitably requires some limitations on personnel; otherwise, having too many couriers could disrupt the company's normal operations. This is why SFTP servers need to limit external connections.
![](https://img-blog.csdnimg.cn/direct/1dc368ab077e40cea26708141630fbe5.png)
>Here, we use a scenario from real life to analogize the model of our data integration platform and upstream systems for file download interaction, to help everyone understand the limitations of the SFTP server, as well as how to maximize our transmission efficiency in the face of limitations from upstream servers. P.S.: The following content is stated from the perspective of our data integration platform.

## 1:1 model (Connecting to one upstream to download files)
Because our goal is to enhance the parallel file transfer efficiency of our data integration platform, we are using our data integration platform as a reference, assuming a scenario where we only interact with one upstream. (For simplicity, we assume that the upstream server allows a maximum of 1 concurrent connection per user and a maximum of 2 open sessions per user).
>``Use Case Description``: Delivery Company is analogous to our data integration platform, and our platform needs to pick up packages from upstream. A Company is analogous to one of the upstreams we need to connect to. The gate at A Company is analogous to the limitations of the upstream server rules. The couriers at Delivery Company are analogous to the ``SSH connections`` that the data integration platform needs to create, and each courier can move several packages at the same time, analogous to the number of ``SFTP Channels`` that can be opened with each ``SSH connection``.

![](https://img-blog.csdnimg.cn/direct/06ee16e0d91146c5aea298739bfbf06d.png)

**Scenario:**
Delivery Company needs to pick up packages at A Company, which has three packages in its warehouse. A Company has the following access restrictions, and access will be denied if not met:
1. At the same time, only three couriers from the same logistics company are allowed to enter.
2. Each worker cannot pick up more than one package.

If Delivery Company wants to ensure high efficiency in picking up packages, under the condition of sufficient couriers, the ideal scenario would be to send three couriers simultaneously to pick up packages, with each person picking up one. However, due to A Company's access restrictions, three couriers attempting to pick up packages simultaneously will result in one courier being denied entry by security, leading to wasted personnel. Therefore, it is only necessary to send two couriers simultaneously, with one person picking up two packages and the other picking up one. This way, all packages can be picked up at once, without wasting personnel, and with high efficiency.

However, in reality, when interfacing with upstream systems, the other party usually does not inform us of any restrictions. It is usually only when we are blocked by the other party that we become aware of them. Also, couriers do have costs. Therefore, as Delivery Company, if we continue to send couriers to A Company to pick up packages, not only might the couriers sent out be blocked, but it would also lead to wasted personnel. Therefore, we need a flexible configuration method that can allocate couriers flexibly based on the upstream system being interfaced with, ensuring that we maximize our pickup efficiency within the constraints imposed by the upstream system.

**We need a configurable pooling technology that not only allows for the reuse of couriers but also needs to allocate resources within the scope of the following principles based on the restrictions imposed by the other party:**
1.	The number of couriers dispatched in parallel <= The number of couriers allowed by the other party to enter the same company at the same time.
2.	The number of packages each courier can pick up in parallel <= The number of packages a single courier can pick up at a time according to the other party's restrictions.

**Translated into computer terminology using the above example, our data integration platform needs to meet the following conditions:**
1.	For A upstream, no more than ``2 SSH Sessions`` can be established at the same time.
2.	Each ``SSH Session`` can only establish ``one SFTP Channel``.

## 1:N model (Connecting to multiple upstreams to download files)
The 1:N model is an extension of the 1:1 model, as our goal is to enhance the parallel file transfer efficiency of our data integration platform. Therefore, here we use our data integration platform as a reference, assuming a scenario where it interacts with multiple upstreams. (For simplicity, we assume that each upstream server allows a maximum of 1 concurrent connection per user and a maximum number of open sessions is 2).
> ``Use Case Description``: Similar to the 1:1 model above, Delivery Company is analogous to our data integration platform, which needs to pick up packages from multiple upstreams. A and B companies are analogous to the two upstreams we need to connect to. The gatekeeping of A and B companies is analogous to the limitations of upstream servers. The couriers of Delivery Company are analogous to the ``SSH`` connections that need to be created on our data integration platform, and each courier can move several packages at the same time, analogous to the number of ``SFTP Channels`` opened by ``each SSH connection``.

![](https://img-blog.csdnimg.cn/direct/76d52df10c5f457cb691c956da533dab.png)
**Scenario**: 

Delivery Company needs to pick up packages from A and B companies. Each company has three packages in their warehouse. Both A and B companies have the following security requirements; if not met, access will be denied:
1.Up to three couriers from the same logistics company can enter at the same time.
2.Each worker cannot pick up more than one package.

Similar to the 1:1 model, if Delivery Company wants to ensure high efficiency in picking up packages, under the condition that there are enough couriers, the ideal scenario is to simultaneously send three couriers from each company to A and B to pick up packages. Each courier can pick up one package, ensuring all packages are picked up in one go. However, due to the security requirements of A and B companies, if three couriers try to pick up packages at the same time, one courier will be denied access by security, resulting in wasted personnel. Therefore, it is necessary to send two couriers from each company, one picking up two packages and the other picking up one. This way, all packages can be picked up in one go without wasting personnel, and efficiency is also high.


**Because this is a one-to-many model, there will be a slight difference from what was mentioned above. In addition to the configurable pooling technology mentioned above, we also need to isolate resources for different upstream and downstream, with the basic principles as follows:**
1.	The number of couriers dispatched in parallel <= The number of couriers allowed by the other party to enter the same company at the same time.
2.	The number of packages each courier picks up in parallel <= The number of packages a courier can pick up at the same time according to the other party's limit.
3.	The couriers allocated to each company at the same time are independent and will not compete for resources.

**Translated into computer terminology using the above example, our data integration platform needs to:**
1.	Not exceed ``2 SSH Sessions`` for A and B at the same time.
2.	Each ``SSH Session`` can only establish ``one Sftp Channel``.
3. The connection pools for A and B's ``SSH Sessions`` are isolated and will not compete with each other.


# Solutions

Based on the ideas mentioned earlier, we already have a preliminary form of a configurable connection pool. The abstracted connection pool model needs to meet the following conditions:

1.	For each connected SFTP server, no more than ``X SSH Sessions`` can be allocated at the same time.
2.	For each ``SSH Session`` allocated to each connected SFTP server, a maximum of ``Y SFTP Channels`` can be established.
3. The ``SSH Session`` connection pool for each connected SFTP server is isolated and will not compete with each other.


In the above, ``X`` represents the ``maximum number of SSH Session connections`` we need to configure, and ``Y`` represents the ``maximum number of Channels`` that can be created for ``each SSH Session``. Both of these values need to be adjusted based on the limitations of the SFTP servers we are connecting to and our own server situation.

To avoid wasting ``SSH Session`` resources in the connection pool, we also need to further optimize by limiting the creation of a new SSH Session to only when each SSH Session needs to create ``Y`` ``SFTP Channels``, rather than creating a new ``SSH Session`` for every request. This will ensure that ``each SSH Session`` is fully utilized and will also reduce the system's load.

**The optimized connection pool model is as follows:**
1.	For each connected SFTP server, no more than ``X`` ``SSH Sessions`` can be allocated at the same time.
2.	Each SSH Session allocated for each connected SFTP server can establish a maximum of ``Y`` ``SFTP Channels``.
3. The ``SSH Session`` connection pool for each connected SFTP server is isolated and will not compete with each other.
4. When multiple threads request to obtain an ``SSH Session`` for a particular SFTP server, if the ``SFTP Channel count`` of the ``first SSH Session`` in the ``current SSH Session pool`` has not exceeded ``Y``, the ``same SSH Session`` instance will be returned. If the ``SFTP Channel count`` of the ``first SSH Session`` in the ``current SSH Session pool`` has already exceeded the configured ``Y`` value, ``a second SSH Session`` will be created, and so on, until the ``Nth SSH Session`` is created, where ``N <= X``.

The process for each upstream connection pool request is as follows:
![AI生产那个的流程图](https://img-blog.csdnimg.cn/direct/179340b78a8d44e7a0a91d341488fd68.png)

# Code implementation
## SFTP Session Pool
This is an implementation of an ··SFTP session pool··, which uses the JSch library to create and manage SFTP sessions (``Session``) and channels (``ChannelSftp``). The main purpose of this session pool is to reuse already created SFTP sessions and channels to improve the efficiency of file transfers.

**The following are the main functionalities and design considerations of this class:**

- **Session and Channel Creation and Management:** This class uses the getSession method to obtain a session from the pool. If no session is available, it creates a new one. Then, it uses the ``getChannel`` method to open a new ``SFTP channel`` from a session.

- **Concurrency Control:** This class uses a ``Semaphore`` object to limit the maximum number of sessions per host (``maxSessionsPerHostSemaphore``) and the maximum number of channels per session (``channelCounts``). This prevents excessive concurrent connections from exhausting system resources.

- **Exception Handling:** If an exception occurs while creating a session or opening a channel, this class removes the invalid session from the pool and releases the corresponding ``Semaphore permit``. This ensures that the pool only contains ``valid sessions and channels``.

- **Session and Channel Reuse:** If all channels of a session have been closed, this class closes the session and removes it from the pool. Otherwise, it returns the session to the pool for reuse in subsequent requests.

- **Key Lock:** This is used to prevent multiple threads holding the same (``host+port+username``) from creating multiple Sessions when obtaining a Session, which would result in more Sessions being created than expected.

- **Session Release:**  If the current Session still has Channels in use (indicating that other threads are using that Session), it is returned to the connection pool. Otherwise, it is closed.

The design of this class provides an effective way to manage SFTP sessions and channels, allowing them to be reused by multiple requests, thereby improving the efficiency of file transfers.
```java
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

    /**
     * get session with keyLock
     *
     * @param host
     * @param port
     * @param username
     * @param password
     * @param timeout
     * @param unit
     * @param lockKey
     * @return
     * @throws JSchException
     * @throws InterruptedException
     */
    public Session getSession(String host, int port, String username, String password, long timeout, TimeUnit unit, String lockKey) throws JSchException, InterruptedException {
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


    /**
     * Return the session if current session is till used by another thread ,otherwise close it.
     * @param session
     */
    public synchronized void returnOrCloseSession(Session session) {
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

```

## SftpConnectionPoolFactory
To connect to different upstreams, we need to implement connection pool isolation. Here, we create a factory class for the caller, which allows for the reuse of independent connection pools through different keys (``host+port+username``). This is because some SFTP servers are shared among multiple users, and the file transfer requirements of different users should be able to be processed in parallel. Therefore, here, the connection pool needs to be resource isolated.

```java
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
```

## SftpFileProcessThread
Here, we create a file processing thread to simulate file processing. When obtaining the ``Session`` object, we use ``host + ":" + username`` as a lock to prevent concurrent creation of connections from exceeding expectations.

```java
package pool.demo;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import pool.common.utils.SftpSessionPool;
import pool.exceptions.NoAvailableSessionException;

import java.util.Vector;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SftpFileProcessThread extends Thread {
    private int maxRetries = 3;
    private SftpSessionPool pool;
    private String host;
    private int port;
    private String username;
    private String password;
    private String remoteSftpPath;

    public SftpFileProcessThread(SftpSessionPool pool, String host, int port, String username, String password, String remoteSftpPath, int maxRetries) {
        this.pool = pool;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.remoteSftpPath = remoteSftpPath;
        this.maxRetries = maxRetries;
    }

    public void run() {
        Session session = null;
        ChannelSftp channelSftp = null;
        try {
            int retries = 0;
            while (true) {
                try {
                    // session = pool.getSession(host, port, username, password, 10, TimeUnit.SECONDS, "");
                    session = pool.getSession(host, port, username, password, 10, TimeUnit.SECONDS, host + ":" + username); //get session with lock
                    break; // if getSession() is successful, break the loop
                } catch (NoAvailableSessionException e) {
                    if (++retries > this.maxRetries) {
                        throw e; // if exceeded max retries, rethrow the exception
                    }
                    log.info("Thread {}:Failed to get session in this round. Start to retry now. Current retry count is {}. Request session detail: {}", this.getId(), retries, this.host + ":" + this.username);
                    // if not exceeded max retries, sleep for a while and then continue the loop to retry
                    Thread.sleep(5000);
                }
            }
            log.info("Thread {} got session {}. Session detail: {}", this.getId(), session, session.getHost() + ":" + session.getUserName());

            channelSftp = pool.getChannel(session);
            channelSftp.connect();
            // ... use the session and channel
            Vector<ChannelSftp.LsEntry> list = channelSftp.ls(this.remoteSftpPath);
            for (ChannelSftp.LsEntry entry : list) {
                // System.out.println(entry.getFilename());
                //Simulate file process
                Thread.sleep(5000);
            }
        } catch (Exception e) {
            log.error("Thread {} failed to process", this.getId(), e);
        } finally {
            if (channelSftp != null) {
                channelSftp.disconnect();
            }
            if (session != null) {
                pool.returnOrCloseSession(session);
                log.info("Thread {} closed session {}. Session detail: {}", this.getId(), session, this.host + ":" + this.username);
            }
        }
    }
}

```
## SftpService
Here is the program entry, where we obtain the connection pool configuration information from the database configuration. Then, by default, we start ``10`` threads to simulate requesting the connection pool and processing file tasks. Later, when we perform testing, we need to change this number of threads to demonstrate.

```java
package pool.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pool.common.utils.SftpConnectionPoolFactory;
import pool.common.utils.SftpSessionPool;
import pool.dataobject.SftpConfig;
import pool.demo.SftpFileProcessThread;
import pool.repositories.SftpConfigRepository;

import java.util.List;

@Slf4j
@Service
public class SftpService {

    private final SftpConfigRepository sftpConfigRepository;
    // final static Map<String, SftpSessionPool> map = new ConcurrentHashMap();

    public SftpService(SftpConfigRepository sftpConfigRepository) {
        this.sftpConfigRepository = sftpConfigRepository;
    }

    public void initializeConnectionPools() {
        // Load all SFTP configs from the database
        List<SftpConfig> configs = sftpConfigRepository.findAll();
        //Indicate how many files need to be processed in the same time
        int max_concurrent_opening_files = 10;

        String testPath = "/";
        // Initialize a connection pool for each config
        for (SftpConfig config : configs) {
            log.info("config->{}", config);
            //map.put(config.getHost() + config.getUsername(), new SftpSessionPool(config.getMaxSessions(), config.getMaxChannels()));
            SftpSessionPool sessionPool = SftpConnectionPoolFactory.getInstance().getSessionPool(config.getHost(), 22, config.getUsername(), config.getMaxSessions(), config.getMaxChannels());
            //Simulate each sftp profile is being used by multiple threads for file process
            for (int i = 0; i < max_concurrent_opening_files; i++) {
                SftpFileProcessThread thread = new SftpFileProcessThread(sessionPool, config.getHost(), config.getPort(), config.getUsername(), config.getPassword(), testPath, 0);
                thread.start();
            }
        }
    }
}
```

## SFTP_CONFIG Table
Here, I created a configuration table to set the upper limit parameters for the connection pool. In this context, JSCH's ``MaxSession`` corresponds to the server's ``MaxStartups`` parameter, and JSCH's ``MaxChannel`` corresponds to the server's ``MaxSessions`` parameter.

```sql
CREATE TABLE SFTP_CONFIG (
    ID INT AUTO_INCREMENT PRIMARY KEY,
    HOST VARCHAR(255),
    PORT INT,
    USERNAME VARCHAR(255),
    PASSWORD VARCHAR(255),
    MAXSESSIONS INT,
    MAXCHANNELS INT
);

```

# Test connection pool
## 1.Not exceeding server limits.
1.1 Here, I set the ``MaxStartups`` for the SFTP server to ``2`` and ``MaxSession`` to ``5``. Setting the limits of SFTP server lower here is for easier testing.
```shell
parallels@ubuntu-linux-22-04-desktop:~$ grep -i "MaxStartups" /etc/ssh/sshd_config
MaxStartups 2
parallels@ubuntu-linux-22-04-desktop:~$ grep -i "MaxSessions" /etc/ssh/sshd_config
MaxSessions 5
parallels@ubuntu-linux-22-04-desktop:~$ 
```
1.2 Change the database configuration of the connection pool to ``MaxSession=3, MaxChannel=5`` to match the server configuration above.
```sql
INSERT INTO SFTP_CONFIG (HOST, PORT, USERNAME, PASSWORD, MAXSESSIONS, MAXCHANNELS) VALUES
('192.168.50.58', 22,'parallels', 'test8808', 2, 5);
```
1.3 Next, we will start the program, which will simultaneously open ``10`` threads with the same connection information to access the same SFTP server. We will see if the program meets our expectations. ``10`` threads should only open ``2 SFTP Sessions``, and the connection to the server should not report an error.

```shell
2024-03-09 01:22:03.536  INFO 58516 --- [       Thread-3] pool.demo.SftpFileProcessThread          : Thread 35 got session com.jcraft.jsch.Session@5cae46b5. Session detail: 192.168.50.58:parallels
2024-03-09 01:22:04.072  INFO 58516 --- [       Thread-4] pool.demo.SftpFileProcessThread          : Thread 36 got session com.jcraft.jsch.Session@5cae46b5. Session detail: 192.168.50.58:parallels
2024-03-09 01:22:04.072  INFO 58516 --- [       Thread-7] pool.demo.SftpFileProcessThread          : Thread 39 got session com.jcraft.jsch.Session@5cae46b5. Session detail: 192.168.50.58:parallels
2024-03-09 01:22:05.075  INFO 58516 --- [       Thread-8] pool.demo.SftpFileProcessThread          : Thread 40 got session com.jcraft.jsch.Session@5cae46b5. Session detail: 192.168.50.58:parallels
2024-03-09 01:22:05.075  INFO 58516 --- [       Thread-6] pool.demo.SftpFileProcessThread          : Thread 38 got session com.jcraft.jsch.Session@5cae46b5. Session detail: 192.168.50.58:parallels
2024-03-09 01:22:05.191  INFO 58516 --- [       Thread-9] pool.demo.SftpFileProcessThread          : Thread 41 got session com.jcraft.jsch.Session@17e2aaef. Session detail: 192.168.50.58:parallels
2024-03-09 01:22:06.083  INFO 58516 --- [      Thread-11] pool.demo.SftpFileProcessThread          : Thread 43 got session com.jcraft.jsch.Session@17e2aaef. Session detail: 192.168.50.58:parallels
2024-03-09 01:22:06.084  INFO 58516 --- [       Thread-5] pool.demo.SftpFileProcessThread          : Thread 37 got session com.jcraft.jsch.Session@17e2aaef. Session detail: 192.168.50.58:parallels
2024-03-09 01:22:06.086  INFO 58516 --- [      Thread-12] pool.demo.SftpFileProcessThread          : Thread 44 got session com.jcraft.jsch.Session@17e2aaef. Session detail: 192.168.50.58:parallels
2024-03-09 01:22:07.084  INFO 58516 --- [      Thread-10] pool.demo.SftpFileProcessThread          : Thread 42 got session com.jcraft.jsch.Session@17e2aaef. Session detail: 192.168.50.58:parallels
2024-03-09 01:24:03.692  INFO 58516 --- [       Thread-3] pool.demo.SftpFileProcessThread          : Thread 35 closed session com.jcraft.jsch.Session@5cae46b5. Session detail: 192.168.50.58:parallels
2024-03-09 01:24:04.157  INFO 58516 --- [       Thread-4] pool.demo.SftpFileProcessThread          : Thread 36 closed session com.jcraft.jsch.Session@5cae46b5. Session detail: 192.168.50.58:parallels
2024-03-09 01:24:04.160  INFO 58516 --- [       Thread-7] pool.demo.SftpFileProcessThread          : Thread 39 closed session com.jcraft.jsch.Session@5cae46b5. Session detail: 192.168.50.58:parallels
2024-03-09 01:24:05.169  INFO 58516 --- [       Thread-8] pool.demo.SftpFileProcessThread          : Thread 40 closed session com.jcraft.jsch.Session@5cae46b5. Session detail: 192.168.50.58:parallels
2024-03-09 01:24:05.198  INFO 58516 --- [       Thread-6] pool.demo.SftpFileProcessThread          : Thread 38 closed session com.jcraft.jsch.Session@5cae46b5. Session detail: 192.168.50.58:parallels
2024-03-09 01:24:05.298  INFO 58516 --- [       Thread-9] pool.demo.SftpFileProcessThread          : Thread 41 closed session com.jcraft.jsch.Session@17e2aaef. Session detail: 192.168.50.58:parallels
2024-03-09 01:24:06.176  INFO 58516 --- [      Thread-11] pool.demo.SftpFileProcessThread          : Thread 43 closed session com.jcraft.jsch.Session@17e2aaef. Session detail: 192.168.50.58:parallels
2024-03-09 01:24:06.182  INFO 58516 --- [       Thread-5] pool.demo.SftpFileProcessThread          : Thread 37 closed session com.jcraft.jsch.Session@17e2aaef. Session detail: 192.168.50.58:parallels
2024-03-09 01:24:06.182  INFO 58516 --- [      Thread-12] pool.demo.SftpFileProcessThread          : Thread 44 closed session com.jcraft.jsch.Session@17e2aaef. Session detail: 192.168.50.58:parallels
2024-03-09 01:24:07.185  INFO 58516 --- [      Thread-10] pool.demo.SftpFileProcessThread          : Thread 42 closed session com.jcraft.jsch.Session@17e2aaef. Session detail: 192.168.50.58:parallels

```
From the above logs, it can be seen that although ``10`` threads all hold the ``same SFTP connection`` information and request connections from the pool in parallel, they will only get ``2 SFTP Session instances`` instead of ``10``. Moreover, when the number of Channel objects held by the ``SSH Session`` in the pool has not yet reached the configured limit of ``5``, the ``same SSH Session instance`` will be allocated. Only when the ``current SSH Session`` object in the pool holds more than ``5`` Channel objects will the next SFTP Session object be allocated, to ensure that SFTP Session is not wasted. Therefore, the connection pool currently meets our expected effects.
 
## 2.Exceeding the server's ``MaxSessions``
 
2.1 The server configuration is the same as above, with ``MaxStartups`` set to ``2`` and ``MaxSession`` set to ``5``.
```shell
parallels@ubuntu-linux-22-04-desktop:~$ grep -i "MaxStartups" /etc/ssh/sshd_config
MaxStartups 2
parallels@ubuntu-linux-22-04-desktop:~$ grep -i "MaxSessions" /etc/ssh/sshd_config
MaxSessions 5
parallels@ubuntu-linux-22-04-desktop:~$ 
```
2.2 Change the database configuration of the connection pool to ``MaxSession=3, MaxChannel=1``. The purpose is to test whether the program will report an error when multiple threads process files in parallel, given that the ``maximum Channel number`` in the connection pool configuration exceeds the server's limit.

```sql
INSERT INTO SFTP_CONFIG (HOST, PORT, USERNAME, PASSWORD, MAXSESSIONS, MAXCHANNELS) VALUES
('192.168.50.57', 22,'parallels', 'test8808', 1, 6);
```
2.3 Next, we will start the program, which will simultaneously open ``6`` threads with the same connection information to access the same SFTP server. We will see if the program meets our expectations. If the program meets our expectations, ``6`` threads will simultaneously use ``1 SSH Session`` to try to open ``6`` Channels. When the ``6th`` thread starts to open a Channel for file processing, it should begin to report errors, because it has exceeded the server's ``MaxSession=5``.
```shell
2024-03-09 15:30:16.725  INFO 74635 --- [           main] pool.services.SftpService                : config->SftpConfig(id=1, host=192.168.50.57, port=22, username=parallels, password=test8808, maxSessions=1, maxChannels=6)
2024-03-09 15:30:17.180  INFO 74635 --- [       Thread-6] pool.demo.SftpFileProcessThread          : Thread 38 got session com.jcraft.jsch.Session@63c4e36c. Session detail: 192.168.50.57:parallels
2024-03-09 15:30:17.257  INFO 74635 --- [       Thread-3] pool.demo.SftpFileProcessThread          : Thread 35 got session com.jcraft.jsch.Session@63c4e36c. Session detail: 192.168.50.57:parallels
2024-03-09 15:30:17.257  INFO 74635 --- [       Thread-5] pool.demo.SftpFileProcessThread          : Thread 37 got session com.jcraft.jsch.Session@63c4e36c. Session detail: 192.168.50.57:parallels
2024-03-09 15:30:17.259  INFO 74635 --- [       Thread-8] pool.demo.SftpFileProcessThread          : Thread 40 got session com.jcraft.jsch.Session@63c4e36c. Session detail: 192.168.50.57:parallels
2024-03-09 15:30:17.264  INFO 74635 --- [       Thread-7] pool.demo.SftpFileProcessThread          : Thread 39 got session com.jcraft.jsch.Session@63c4e36c. Session detail: 192.168.50.57:parallels
2024-03-09 15:30:17.268  INFO 74635 --- [       Thread-4] pool.demo.SftpFileProcessThread          : Thread 36 got session com.jcraft.jsch.Session@63c4e36c. Session detail: 192.168.50.57:parallels
2024-03-09 15:30:17.278 ERROR 74635 --- [       Thread-4] pool.demo.SftpFileProcessThread          : Thread 36 failed to process

com.jcraft.jsch.JSchException: channel is not opened.
	at com.jcraft.jsch.Channel.sendChannelOpen(Channel.java:835) ~[jsch-0.2.16.jar:0.2.16]
	at com.jcraft.jsch.Channel.connect(Channel.java:161) ~[jsch-0.2.16.jar:0.2.16]
	at com.jcraft.jsch.Channel.connect(Channel.java:155) ~[jsch-0.2.16.jar:0.2.16]
	at pool.demo.SftpFileProcessThread.run(SftpFileProcessThread.java:54) ~[classes/:na]
```
From the logs above, we can see that when the number of connections exceeds the server's ``maximum Channel`` configuration of ``5``, the server begins to refuse requests, which aligns with our test expectations. Therefore, the configuration range of our connection pool must be less than or equal to the server's configuration; otherwise, it may cause interruptions in file transfers, ``it's better to under-configure rather than over-configure.``

## 3.Exceeding the server's ``MaxStartups``
 
3.1 The server configuration is the same as above, with ``MaxStartups`` set to ``2`` and ``MaxSession`` set to ``5``.
```shell
parallels@ubuntu-linux-22-04-desktop:~$ grep -i "MaxStartups" /etc/ssh/sshd_config
MaxStartups 2
parallels@ubuntu-linux-22-04-desktop:~$ grep -i "MaxSessions" /etc/ssh/sshd_config
MaxSessions 5
parallels@ubuntu-linux-22-04-desktop:~$ 
```

3.2 Change the database configuration of the connection pool to ``MaxSession=3, MaxChannel=1``, with the aim of testing whether the program will encounter errors when multiple threads concurrently create ``SSH Session`` when the ``maximum Session number`` configured in the connection pool exceeds the server's limit.
```sql
INSERT INTO SFTP_CONFIG (HOST, PORT, USERNAME, PASSWORD, MAXSESSIONS, MAXCHANNELS) VALUES
('192.168.50.57', 22,'parallels', 'test8808', 3, 1);
```
3.3 Modify the code in ``SftpFileProcessThread`` to change the connection method to a lock-free approach, so that we can see the server's error when the number of concurrently created ``Session`` objects exceeds the server's limit. Since we have configured ``MaxStartups`` to ``2``, we need to simulate multiple threads concurrently creating more than ``2 Session`` objects waiting for verification to see the effect. If we use a locking mode, the creation of Session will become serial, and only after verification passes will it proceed to the ``next Session``, so the server ``will not have multiple Session objects`` waiting for verification at the same time.
```java
 session = pool.getSession(host, port, username, password, 10, TimeUnit.SECONDS, "");
 //session = pool.getSession(host, port, username, password, 10, TimeUnit.SECONDS, host + ":" + username); //get session with lock
```
3.4 Next, we will start the program, which will simultaneously open ``3`` threads with the same connection information to access the same SFTP server. Let's see if the program meets our expectations. If the program meets our expectations, ``3`` threads will simultaneously create ``3 SSH Sessions``. When the ``3rd`` thread attempts to create an SSH Session, it should report an error because it exceeds the server's maximum concurrent Session limit ``MaxStartups=2``.
```shell
2024-03-09 15:55:23.367  INFO 7577 --- [           main] pool.services.SftpService                : config->SftpConfig(id=1, host=192.168.50.57, port=22, username=parallels, password=test8808, maxSessions=3, maxChannels=1)
2024-03-09 15:55:23.455 ERROR 7577 --- [       Thread-5] pool.demo.SftpFileProcessThread          : Thread 37 failed to process

com.jcraft.jsch.JSchException: Session.connect: java.net.SocketException: Connection reset
	at com.jcraft.jsch.Session.connect(Session.java:570) ~[jsch-0.2.16.jar:0.2.16]
	at com.jcraft.jsch.Session.connect(Session.java:199) ~[jsch-0.2.16.jar:0.2.16]
	at pool.common.utils.SftpSessionPool.createNewSession(SftpSessionPool.java:132) ~[classes/:na]
	at pool.common.utils.SftpSessionPool.getSessionFromPool(SftpSessionPool.java:109) ~[classes/:na]
	at pool.common.utils.SftpSessionPool.getSession(SftpSessionPool.java:57) ~[classes/:na]
	at pool.demo.SftpFileProcessThread.run(SftpFileProcessThread.java:39) ~[classes/:na]
Caused by: java.net.SocketException: Connection reset
	at java.net.SocketInputStream.read(SocketInputStream.java:210) ~[na:1.8.0_231]
	at java.net.SocketInputStream.read(SocketInputStream.java:141) ~[na:1.8.0_231]
	at java.net.SocketInputStream.read(SocketInputStream.java:224) ~[na:1.8.0_231]
	at com.jcraft.jsch.IO.getByte(IO.java:84) ~[jsch-0.2.16.jar:0.2.16]
	at com.jcraft.jsch.Session.connect(Session.java:276) ~[jsch-0.2.16.jar:0.2.16]
	... 5 common frames omitted

2024-03-09 15:55:23.900  INFO 7577 --- [       Thread-3] pool.demo.SftpFileProcessThread          : Thread 35 got session com.jcraft.jsch.Session@4777f0b1. Session detail: 192.168.50.57:parallels
2024-03-09 15:55:23.900  INFO 7577 --- [       Thread-4] pool.demo.SftpFileProcessThread          : Thread 36 got session com.jcraft.jsch.Session@326d5e63. Session detail: 192.168.50.57:parallels
```
From the logs above, it can be seen that at the same time, only ``2`` out of ``3`` threads can successfully create a ``Session``, while the other thread reports an error when trying to create a ``Session``. This is because it exceeds the server's maximum concurrent ``Session`` limit and is denied access.

# Summary
When using a configurable connection pool for access, it's best to confirm with the upstream how many ``Session`` and ``Channel`` numbers their server can handle when connecting to the upstream. It's better to configure less rather than more. However, for general upstreams, if they are using Linux servers, the default values are as mentioned at the beginning: ``MaxSessions=10, MaxStartups=10:30:60``. Our client-side connection pool conservative configuration can be set to ``MaxSession=3, MaxChannel=10``, which can handle up to ``30`` files in parallel. It's generally not recommended to set ``MaxSession`` too high, as it consumes a lot of system resources. Many upstreams use other SFTP service management tools, but the basic limit parameters are similar. There are still many areas in the connection pool that need to be improved, and everyone can optimize it according to their needs.

# Github Source Code
https://github.com/EvanLeung08/sftp-session-pool
