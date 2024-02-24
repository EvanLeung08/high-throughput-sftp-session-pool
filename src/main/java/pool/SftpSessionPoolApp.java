package pool;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import pool.services.SftpService;

@EnableScheduling
@SpringBootApplication
public class SftpSessionPoolApp {

    private final SftpService sftpService;

    public SftpSessionPoolApp(SftpService sftpService) {
        this.sftpService = sftpService;
    }

    public static void main(String[] args) {
        SpringApplication.run(SftpSessionPoolApp.class, args);
    }

    @Bean
    public ApplicationRunner applicationRunner() {
        return args -> sftpService.initializeConnectionPools();
    }
}
