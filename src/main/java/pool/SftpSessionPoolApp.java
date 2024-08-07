package pool;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import pool.common.utils.TraceIdUtil;
import pool.services.SftpService;

@Slf4j
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
        //获取父线程TraceId
        String parentTraceId = TraceIdUtil.getTraceId();
        MDC.put("ParentTraceId", parentTraceId);
        // 生成一个新的TraceId
        String traceId = TraceIdUtil.generateTraceId();
        MDC.put("TraceId", traceId);
        try {
            log.info("Scheduler started...");
            return args -> sftpService.initializeConnectionPools();
        }finally {
            MDC.clear();
        }
    }
}
