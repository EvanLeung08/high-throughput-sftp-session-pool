package pool.common.utils;

import com.alibaba.ttl.TransmittableThreadLocal;

public class TraceIdUtil {
    private static final TransmittableThreadLocal<String> TRACE_ID = new TransmittableThreadLocal<>();
    private static final SnowflakeIdWorker ID_GENERATOR = new SnowflakeIdWorker(0, 0);

    public static String generateTraceId() {
        // 使用Snowflake ID作为traceId
        String traceId = Long.toString(ID_GENERATOR.nextId());
        TRACE_ID.set(traceId);
        return traceId;
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static void clearTraceId() {
        TRACE_ID.remove();
    }
}
