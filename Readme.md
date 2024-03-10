# 背景

在现代的数据驱动环境中，安全文件传输协议（SFTP）扮演着至关重要的角色，它提供了一种安全、可靠的文件传输方式。我们目前项目是一个大型数据集成平台，跟上下游有很多文件对接是通过SFTP协议，当需要处理大量文件或进行大规模数据迁移时，我们常常会遇到因上下游服务器的限制导致连接访问被中断或者文件传输中断，大大影响系统的文件传输效率。常见的报错例子 ``com.jcraft.jsch.JSchException: Session.connect: java.net.SocketException: Connection reset``、``com.jcraft.jsch.JSchException: connection is closed by foreign host``、``com.jcraft.jsch.JSchException: session is down``、``com.jcraft.jsch.JSchException: channel is not opened`` 等。这些连接问题也一直困扰着我们，那我们有没有办法在上下游有限的资源去最大提高文件并行处理能力呢？目前网上的连接池方案只是简单解决了``SSH Session``复用，但是并没有充分利用同一个``SSH Session``对象中的``SFTP Channel``以减少``Session的``开销，所以当文件量大的时候，还是会因为服务器的``SSH Session``数量限制从而影响文件并行传输效率。最节省资源又能提高文件并行处理方式的做法应该是同时复用``SSH Session``以及其``SFTP Channel``，这样最大并行可以处理的文件数就是 ``SSH Session数*SFTP Channel数``，而不是直接每个文件处理线程开启一个``SSH Session``，只使用``SSH Session``的一个``Channel``。

# 问题原因
所有上下游的SFTP服务器都是连接限制，一般来说默认情况下，常见的Linux服务器默认``MaxSessions``是``10``，``MaxStartups``是``10:30:60``。

这里简单对``MaxSessions``和``MaxStartups``两个配置参数解释下，这个对我们理解SFTP很重要。
- ``MaxStartups 10:30:60``:这里指服务器开始时可以进行的最大未经身份验证的连接数是**10**，当超过第一个数字的连接数时(**10**)，``SSHD`` 将开始随机地拒绝新的连接，在达到第三个数字时(**60**)，``SSHD`` 将拒绝所有新的连接。注意，这里是拒绝未经身份认证的连接数，已经登陆的不会限制，也就是``客户端如果同时创建一堆连接等待认证的话，等待的数量超过这个配置就会被拒绝``。
- ``MaxSessions 10``: 这里是指每个``SSH``连接可以创建多少个通信通道，例如JSCH中的``ChannelSftp``文件处理通道。

如果上下游不对其SFTP服务器做任何设置，那么其服务器最大允许同时并行**10**个SSH连接(超过**10**个连接服务器``SSHD``服务就会随机中断连接，服务开始不稳定)，每个``SSH``连接最多可以打开**10**个``SFTP Channel``。 

**这里要说下``MaxSessions``和``MaxStartups``参数对Java SFTP客户端的影响**

> 以下以目前常用的SFTP框架JSCH为例子。 MaxStartups 和 MaxSessions 是 SSH 守护进程（SSHD）的配置选项，而 JSch 中的 Session 和 ChannelSftp 是 Java 客户端连接到 SSH 服务的对象。它们之间的关系如下：
> - ``MaxStartups``：这是 SSHD 配置的一部分，用于控制并发未经身份验证的连接的数量。这对于防止暴力破解攻击非常有用，因为它限制了同时尝试身份验证的连接数。这个设置对于 JSch 客户端来说，主要影响的是当你尝试并发创建多个 Session 对象（即多个 SSH 连接）时，服务器端可能会开始拒绝额外的连接。
> - ``MaxSessions``：这也是 SSHD 配置的一部分，用于限制每个网络连接（即每个 SSH Session）可以打开的最大会话（channel）数量。这个设置对于 JSch 客户端来说，主要影响的是在一个 Session 对象上你可以创建多少个 ChannelSftp 对象（或其他类型的 Channel 对象）。如果你尝试超过 MaxSessions 的限制创建更多的 Channel，服务器端可能会拒绝新的 Channel 创建请求。

总的来说，``MaxStartups`` 和 ``MaxSessions`` 是服务器端的限制，它们影响了客户端（例如使用 JSch 的应用程序）可以并行创建多少个 ``Session`` 和 ``Channel``。如果我们要设计一个需要处理大量并发 SFTP 传输的系统，需要考虑这些限制，并在必要时调整服务器的 SSHD 配置。

**以文件下载为例，这里用现实中的模型简单说下目前我们数据集成平台下载文件的场景**
我们数据集成平台需要从不同的上游的下载文件，``A、B、C``公司相当于我们的上游系统，某东快递相当于我们的数据集成系统。如下图所示，以其中一个快递公司的视角，某东快递需要对接多个商家收件，如果想提高收件速度，最理想就是安排不同的员工并行到不同的商家去取件，这样才可以同时最快收件；而以其中一个商家的视角，A公司要应对不同快递公司的并行取件请求，必然需要对人员有些限制，不然快递员太多可能会影响公司的正常运作，这也就是为什么SFTP服务器需要限制外部连接。
![](https://img-blog.csdnimg.cn/direct/1dc368ab077e40cea26708141630fbe5.png)
>这里用现实生活中的场景来类比我们数据集成平台和上游系统进行文件下载交互的模型，方便大家理解SFTP服务器的限制，以及面对上游服务器的限制怎么最大化我们的传输效率。P.S：以下内容是以我们数据集成平台的视角去陈述。

## 1对1模型(对接一个上游下载文件)
因为我们目的是提升我们数据集成平台的并行文件传输效率，所以这里是以我们数据集成平台为参照物，假设只跟一个上游交互的场景。(这里为了简单化说明，假设上游服务器每个用户允许最大并发连接是1，打开最大会话数量是2)。
>``用例说明``: 某东快递类比我们的数据集成平台，我们平台要去上游收件，而A公司类比我们要对接的其中一个上游。A公司的门禁类比上游服务器的限制规则。某东快递的快递员类比数据集成平台需要创建的``SSH``连接，每个快递员可以同时搬运几个货物类比每个``SSH``连接打开的SFTP Channel数量。

![](https://img-blog.csdnimg.cn/direct/06ee16e0d91146c5aea298739bfbf06d.png)
场景: 
某东快递需要上门到A公司取件，A公司有三个件在仓库，A公司有以下门禁要求，不满足就会被拒绝访问：
1.同一个时间允许同一家物流公司的3个快递员进入
2.每个工人不能取超过一个货物

某东快递如果想取件时效性高，在快递员充足的情况下，最理想是派3个快递员同时取件，每人拿一个就可以一次拿完。但是由于A公司有门禁要求，3名快递员同时取取件，会有一位快递员会被保安拒绝，造成人员浪费。因此只需要同时派2名快递员，一个人拿2个，另一个人拿一个就可以。这样子就能一次拿完，也不会人员浪费，时效性也高。

但是真实情况，在对接上游时，对方一般都不会告诉我们有什么限制，通常都是被对方拦截才知道，而且快递员也是有成本的。所以我们作为某东快递，如果一直派快递员去A公司取件，除了可能派出去的快递员会被拦截，还会造成人员浪费。因此我们需要一种灵活配置的方式，可以基于对接的上游，灵活分配符合需求的快递员，这样可以在符合上游限制范围内，最大化我们的取件效率。

**我们需要有一种可配置的池化技术，除了可以重复利用快递员，还需要在对方限制的范围基于以下原则合理分配:**
1.	并行派出的快递员数<=对方限制同一时间允许相同公司进入的快递员数
2.	每位快递员并行取件的数量<=对方限制每个快递员单次同时取件的数量

**用上面的例子转化成计算机术语，就是需要我们数据集成平台满足以下条件:**
1.	为A上游同一时间不能建立超过**2**个``SSH Session``
2.	每个``SSH Session``最多只能建立一个``Sftp Channel``

## 1对多模型(对接多个上游下载文件)
1对多模型是1对1模型的延伸，因为我们目的是提升我们数据集成平台的并行文件传输效率，所以这里是以我们数据集成平台为参照物，假设只跟多个上游交互的场景。(这里为了简单化说明，假设上游服务器每个用户允许最大并发连接是1，打开最大会话数量是2)。
>``用例说明``: 跟以上1对1模型类似，某东快递类比我们的数据集成平台，我们平台要去多个上游收件，而A、B公司类比我们要对接的两个上游。A、B公司的门禁类比上游服务器的限制规则。某东快递的快递员类比数据集成平台需要创建的SSH连接，每个快递员可以同时搬运几个货物类比每个SSH连接打开的SFTP Channel数量。

![](https://img-blog.csdnimg.cn/direct/76d52df10c5f457cb691c956da533dab.png)
场景: 
某东快递需要上门到A、B公司取件，A、B公司各有三个件在仓库，A、B公司均有以下门禁要求，不满足就会被拒绝访问：
1.同一个时间允许同一家物流公司的3个快递员进入
2.每个工人不能取超过一个货物

跟1对1模型类似，某东快递如果想取件时效性高，在快递员充足的情况下，最理想是同时为两家公司各派3名快递员到A、B公司取件，每人拿一个就可以一次拿完。但是由于A、B公司有门禁要求，3名快递员同时取取件，会有一位快递员会被保安拒绝，造成人员浪费。因此只需要同时各派2名快递员，一个人拿2个，另一个人拿一个就可以。这样子就能一次拿完，也不会人员浪费，时效性也高。


**因为这里是一对多模型，跟上面会有一点不一样的地方，我们除了需要上面提到的可配置的池化技术，还需要针对不同上下游做资源隔离，基本原则如下:**
1.	并行派出的快递员数<=对方限制同一时间允许相同公司进入的快递员数
2.	每位快递员并行取件的数量<=对方限制每个快递员单次同时取件的数量
3.	同一时间为每个公司分配的快递员是独立的，不会资源竞争

**用上面的例子转化成计算机术语，就是需要我们数据集成平台:**
1.	为A、B上游同一时间不能超过**2**个``SSH Session``
2.	每个``SSH Session``最多只能建立一个``Sftp Channel``
3. A、B的``SSH Session``连接池是隔离的，不会互相竞争


# 解决方案

基于前文提到的思路，我们已经有了可配置连接池的雏形，抽象出来的连接池模型需要符合如下条件:
1.	为每台对接的SFTP服务器同一时间不能分配超过``X``个``SSH Session``
2.	为每台对接的SFTP服务器分配的每个``SSH Session``最多只能建立``Y``个``SFTP Channel``
3. 为每台对接的SFTP服务器分配的``SSH Session``连接池是隔离的，不会互相竞争

以上``X``代表的我们需要配置的最大``SSH Session``连接数，``Y``代表我们需要配置的每个``SSH Session``最大能创建的``Channel``数，这两个值都需要根据对接的SFTP服务器的限制和自身服务器情况去调整。

为了避免连接池浪费``SSH Session``资源，我们还需要做进一步优化，就是要限制每个``SSH Session``需要创建满``Y``个``SFTP Channel``才创建一个新的``SSH Session``，而不是每次请求都先创建一个新的``SSH Session``，这样会导致每个``SSH Session``都没被充分利用，而且也会增加系统的负担。

**优化后的连接池模型如下：**
1.	为每台对接的SFTP服务器同一时间不能分配超过``X``个``SSH Session``
2.	为每台对接的SFTP服务器分配的每个SSH Session最多只能建立``Y``个``SFTP Channel``
3. 为每台对接的SFTP服务器分配的``SSH Session``连接池是隔离的，不会互相竞争
4. 多个线程请求获取对某台SFTP服务器``SSH Session``时，如果当前``SSH Session``池中的第一个Session的``SFTP Channel`` 数还没超过``Y``，则会返回同一个``SSH Session``实例，假如当前的``SSH Session``池的第一个``Session``创建的``SFTP Channel``数已经超过配置的``Y``值，则创建第二个``SSH Session``，如此类推，直到创建第N个``SSH Session``，N <``X``

对于每个上游分配的连接池请求流程如下:
![AI生产那个的流程图](https://img-blog.csdnimg.cn/direct/179340b78a8d44e7a0a91d341488fd68.png)

# 代码实现
## SFTP Session Pool
这是一个 SFTP 会话池的实现，它使用了 JSch 库来创建和管理 SFTP 会话（``Session``）和通道（``ChannelSftp``）。这个会话池的主要目的是复用已经创建的 SFTP 会话和通道，以提高文件传输的效率。

以下是这个类的主要功能和设计考虑：

- **会话和通道的创建和管理：** 这个类使用 ``getSession`` 方法来从池中获取一个会话，如果没有可用的会话，它会创建一个新的会话。然后，它使用 ``getChannel ``方法来从一个会话中打开一个新的 SFTP 通道。

- **并发控制：** 这个类使用了 ``Semaphore ``对象来限制每个主机的最大会话数（``maxSessionsPerHostSemaphore``）以及每个会话的最大通道数（``channelCounts``）。这样可以防止过多的并发连接导致系统资源耗尽。

- **异常处理：** 如果在创建会话或打开通道时发生异常，这个类会从池中移除无效的会话，并释放相应的 ``Semaphore ``许可。这样可以确保池中只包含有效的会话和通道。

- **会话和通道的复用：** 如果一个会话的所有通道都已经关闭，这个类会关闭这个会话并从池中移除它。否则，它会将这个会话放回池中，以便后续的请求可以复用它。

- **Key锁：** 这是用来防止多个持有相同的(``host+port+username``)的线程取Session的时候会出现并发，导致创建的``Session``数比预期多。

- **会话释放** - 如果当前``Session``仍有``Channel``在使用(意味着其他线程在使用该``Session``)，则会放回连接池。否则，会关闭。

这个类的设计提供了一个有效的方式来管理 SFTP 会话和通道，使得它们可以被多个请求复用，从而提高文件传输的效率。
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

## SftpConnectionBuilder
为了对接不同的上游，要实现连接池隔离，这里创建一个Builder给调用者，通过不同的Key(``host+port+username)``可以复用独立的连接池，这里是因为考虑到有些SFTP Server是多个用户复用的，不同的用户文件传输需求应该是可以并行处理，因此这里连接池要资源隔离。

```java
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
```

## SftpFileProcessThread
这里创建一个文件处理线程来模拟文件处理，这里获取``Session``对象时使用``host + ":" + username``作为锁是防止获取``Session``时并发导致创建的连接数超过预期。
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
这里是程序入口，从数据库配置获取连接池配置信息，然后默认开启了``10``条线程去模拟请求连接池并处理文件任务，后面我们进行测试时，需要改这个线程数来演示。
```java
package pool.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pool.common.utils.SftpConnectionBuilder;
import pool.common.utils.SftpSessionPool;
import pool.dataobject.SftpConfig;
import pool.demo.SftpFileProcessThread;
import pool.repositories.SftpConfigRepository;

import java.util.List;

@Slf4j
@Service
public class SftpService {

    private final SftpConfigRepository sftpConfigRepository;

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

            SftpSessionPool sessionPool = SftpConnectionBuilder.getInstance(config.getMaxSessions(), config.getMaxChannels()).getSessionPool(config.getHost(), 22, config.getUsername());
            //Simulate each sftp profile is being used by multiple threads for file process
            for (int i = 0; i < max_concurrent_opening_files; i++) {
                SftpFileProcessThread thread = new SftpFileProcessThread(sessionPool, config.getHost(), config.getPort(), config.getUsername(), config.getPassword(), testPath, 0);
                thread.start();
            }
        }
    }
}
```

## SFTP_CONFIG表
这里我创建了一张配置表，用来配置连接池的上限参数，这里JSCH的 ``MaxSession``对应的是服务器``MaxStartups``参数，JSCH的``MaxChannel``对应的是服务器的``MaxSessions``参数
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

# 测试连接池
## 1.不超过服务器限制
1.1 这里我把SFTP服务器的``MaxStartups``设置成``2``，``MaxSession``设置成``5``。这里把SFTP 的限制设低点是方便测试。
```shell
parallels@ubuntu-linux-22-04-desktop:~$ grep -i "MaxStartups" /etc/ssh/sshd_config
MaxStartups 2
parallels@ubuntu-linux-22-04-desktop:~$ grep -i "MaxSessions" /etc/ssh/sshd_config
MaxSessions 5
parallels@ubuntu-linux-22-04-desktop:~$ 
```
1.2 把连接池的数据库配置改成``MaxSession=3，MaxChannel =5`` 与上面服务器配置一致。
```sql
INSERT INTO SFTP_CONFIG (HOST, PORT, USERNAME, PASSWORD, MAXSESSIONS, MAXCHANNELS) VALUES
('192.168.50.58', 22,'parallels', 'test8808', 2, 5);
```
1.3 接下来我们来启动程序，会同时并行开启**10**个线程持有相同的连接信息去访问同一台SFTP 服务器，我们看看程序是否会符合我们预期，**10**个线程应该只会打开**2**个``SFTP Session``，并且连接服务器不会报错。
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
从以上日志可以看出，**10**条线程虽然都并行持有相同的SFTP连接信息请求连接池，但是只会获取到**2**个``SFTP Session``实例而不是**10**个``SFTP Session``，并且当连接池里的``SSH Session``持有的``Channel``还没有达到配置的上限**5**个时，只会分配同一个``SSH Session``实例，当前连接池里的``SSH Session``对象持有的``Channel``超过**5**才会分配下一个``SFTP Session``对象，来确保``SFTP Session``不会浪费。因此目前连接池是符合我们预期想要的效果。
 
## 2.超过服务器``MaxSessions``
 
2.1 服务器配置跟上面一样，``MaxStartups``设置成``2``，``MaxSession``设置成``5``。
```shell
parallels@ubuntu-linux-22-04-desktop:~$ grep -i "MaxStartups" /etc/ssh/sshd_config
MaxStartups 2
parallels@ubuntu-linux-22-04-desktop:~$ grep -i "MaxSessions" /etc/ssh/sshd_config
MaxSessions 5
parallels@ubuntu-linux-22-04-desktop:~$ 
```
2.2 把连接池的数据库配置改成``MaxSession=3，MaxChannel =1`` ，目的是测试当连接池配置的最大``Channel``数大于服务器限制时，程序多个线程并行进行文件处理会不会报错。
```sql
INSERT INTO SFTP_CONFIG (HOST, PORT, USERNAME, PASSWORD, MAXSESSIONS, MAXCHANNELS) VALUES
('192.168.50.57', 22,'parallels', 'test8808', 1, 6);
```
2.3 接下来我们来启动程序，会同时并行开启**6**个线程持有相同的连接信息去访问同一台SFTP 服务器，我们看看程序是否会符合我们预期。如果程序符合预期的话，**6**个线程会同时使用1个``SSH Session``去尝试打开**6**个Channel，第**6**个线程开始打开``Channel``进行文件处理的时候应该会开始报错，因为已经超过了服务器所能承受的 ``MaxSession=5``。
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
从上面日志可以看到，当超过服务器最大``Channel``配置``5``时，服务器就会开始拒绝服务，如何我们测试预期。因此我们连接池的配置范围一定要小于等于服务器的配置，否则可能会引起文件传输中断，``也就是宁愿少配也不要多配``。

## 3.超过服务器``MaxStartups``
 
3.1 服务器配置跟上面一样，``MaxStartups``设置成``2``，``MaxSession``设置成``5``。
```shell
parallels@ubuntu-linux-22-04-desktop:~$ grep -i "MaxStartups" /etc/ssh/sshd_config
MaxStartups 2
parallels@ubuntu-linux-22-04-desktop:~$ grep -i "MaxSessions" /etc/ssh/sshd_config
MaxSessions 5
parallels@ubuntu-linux-22-04-desktop:~$ 
```

3.2 把连接池的数据库配置改成``MaxSession=3，MaxChannel =1`` ，目的测试当连接池配置的最大``Session``数大于服务器限制时，程序多个线程并发创建``SSH Session``会不会出错。
```sql
INSERT INTO SFTP_CONFIG (HOST, PORT, USERNAME, PASSWORD, MAXSESSIONS, MAXCHANNELS) VALUES
('192.168.50.57', 22,'parallels', 'test8808', 3, 1);
```
3.3 修改``SftpFileProcessThread``代码中获取连接方式改成无锁方式，这样可以看到并发创建``Session``数量超过服务器限制时服务器的报错。因为``MaxStartups``上面我们配置了``2``，需要同时多个线程模拟并发创建超过2个等待验证的``Session``才能看到效果，如果使用有锁模式，创建``Session``就会变成串行，验证通过才会到下一个``Session``，服务器就不会同时有多个等待的验证的``Session``。
```java
 session = pool.getSession(host, port, username, password, 10, TimeUnit.SECONDS, "");
 //session = pool.getSession(host, port, username, password, 10, TimeUnit.SECONDS, host + ":" + username); //get session with lock
```
3.4 接下来我们来启动程序，会同时并行开启**3**个线程持有相同的连接信息去访问同一台SFTP 服务器，我们看看程序是否会符合我们预期。如果程序符合预期的话，**3**个线程会同时创建3个``SSH Session``，第**3**个线程尝试创建``SSH Session	``时应该会报错，因为超过了服务器最大 并发``Session``数限制``MaxStartups=2``。
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
从上面日志可以看到，3个线程同一时间只有2个线程能成功创建``Session``，另外一个线程创建``Session``就报错，这是因为超过了服务器最大并发``Session``数限制而被拒绝访问。

# 总结
我们如果使用这种可配置连接池进行访问，对接上游时最好时最好跟上游确认他们服务器可以承受的``Session``数量和``Channel``数量是多少，宁愿少配也不要多配。但是对于一般上游，如果使用的是Linux服务器，默认值就是上面一开始提到的``MaxSessions=10，MaxStartups=10:30:60``，我们客户端连接池保守配置可以设置成``MaxSession=3，MaxChannel=10``，这样子最大并行可以处理``30``个文件，``MaxSession``一般不建议设置太多，非常消耗系统资源。也有很多上游是使用其他SFTP服务管理工具，但是基本限制参数差不多。以上连接池还有很多需要完善的地方，大家可以根据需要自己进行优化。

# Github源代码
https://github.com/EvanLeung08/sftp-session-pool
