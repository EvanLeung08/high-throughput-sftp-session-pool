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