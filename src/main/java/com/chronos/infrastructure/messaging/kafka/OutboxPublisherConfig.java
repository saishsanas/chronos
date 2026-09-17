package com.chronos.infrastructure.messaging.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OutboxPublisherConfig {

    private final String topic;
    private final int batchSize;
    private final int leaseSeconds;
    private final int baseDelaySeconds;
    private final int maxDelaySeconds;
    private final String workerId;

    public OutboxPublisherConfig(
            @Value("${chronos.kafka.topic:chronos.events.v1}") String topic,
            @Value("${chronos.outbox.batch-size:50}") int batchSize,
            @Value("${chronos.outbox.lease-seconds:30}") int leaseSeconds,
            @Value("${chronos.outbox.base-delay-seconds:5}") int baseDelaySeconds,
            @Value("${chronos.outbox.max-delay-seconds:60}") int maxDelaySeconds
    ) {
        this.topic = topic;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.baseDelaySeconds = baseDelaySeconds;
        this.maxDelaySeconds = maxDelaySeconds;
        this.workerId = "chronos-relay-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String getTopic() {
        return topic;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public int getBaseDelaySeconds() {
        return baseDelaySeconds;
    }

    public int getMaxDelaySeconds() {
        return maxDelaySeconds;
    }

    public String getWorkerId() {
        return workerId;
    }

    public long calculateBackoffSeconds(int attempts) {
        if (attempts <= 0) {
            return baseDelaySeconds;
        }
        long delay = (long) baseDelaySeconds * (1L << Math.min(attempts - 1, 6));
        return Math.min(delay, maxDelaySeconds);
    }
}
