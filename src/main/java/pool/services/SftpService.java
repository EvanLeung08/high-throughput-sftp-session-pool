package pool.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pool.dataobject.SftpConfig;
import pool.repositories.SftpConfigRepository;
import pool.demo.TestThread;
import pool.common.utils.SftpSessionPool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SftpService {

    private final SftpConfigRepository sftpConfigRepository;
    final static Map<String, SftpSessionPool> map = new ConcurrentHashMap();

    public SftpService(SftpConfigRepository sftpConfigRepository) {
        this.sftpConfigRepository = sftpConfigRepository;
    }

    public void initializeConnectionPools() {
        // Load all SFTP configs from the database
        List<SftpConfig> configs = sftpConfigRepository.findAll();
        String testPath = "/tmp/evan";
        // Initialize a connection pool for each config
        for (SftpConfig config : configs) {
            log.info("config->{}", config);
            map.put(config.getHost() + config.getUsername(), new SftpSessionPool(config.getMaxSessions(), config.getMaxChannels()));
            //Simulate each sftp profile is being used by multiple threads for file process
            for (int i = 0; i < 3; i++) {
                TestThread thread = new TestThread(map.get(config.getHost() + config.getUsername()), config.getHost(), config.getUsername(), config.getPassword(), testPath);
                thread.start();
            }
        }
    }
}