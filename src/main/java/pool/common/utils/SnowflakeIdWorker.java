package pool.common.utils;

public class SnowflakeIdWorker {
    private long workerId;
    private long sequence = 0L;
    private long twepoch = 1288834974657L;
    private long workerIdBits = 5L;
    private long sequenceBits = 12L;
    private long workerIdShift = sequenceBits;
    private long timestampLeftShift = sequenceBits + workerIdBits;
    private long sequenceMask = -1L ^ (-1L << sequenceBits);
    private long lastTimestamp = -1L;

    public SnowflakeIdWorker(long workerId, long datacenterId) {
        if (workerId > (-1L ^ (-1L << workerIdBits))) {
            throw new IllegalArgumentException("Worker Id can't be greater than " + (-1L ^ (-1L << workerIdBits)));
        }
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long timestamp = timeGen();
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("Clock moved backwards. Refusing to generate id for " + (lastTimestamp - timestamp) + " milliseconds");
        }
        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & sequenceMask;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - twepoch) << timestampLeftShift) | (workerId << workerIdShift) | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    private long timeGen() {
        return System.currentTimeMillis();
    }
}