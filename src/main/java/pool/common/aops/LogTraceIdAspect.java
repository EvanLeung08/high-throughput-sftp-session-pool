package pool.common.aops;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import pool.common.utils.TraceIdUtil;

@Slf4j
@Aspect
@Component
public class LogTraceIdAspect {

    @Around("@annotation(pool.common.annotations.LogTraceId)")
    public Object logTraceId(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取父线程TraceId
        String parentTraceId = TraceIdUtil.getTraceId();
        MDC.put("ParentTraceId", parentTraceId);
        // 生成一个新的TraceId
        String traceId = TraceIdUtil.generateTraceId();
        MDC.put("TraceId", traceId);
        try {
            return joinPoint.proceed();  // 执行方法
        } finally {
            // 在方法执行完毕后清除TraceId
            TraceIdUtil.clearTraceId();
            MDC.clear();
        }
    }
}