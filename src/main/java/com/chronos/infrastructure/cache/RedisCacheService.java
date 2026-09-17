package com.chronos.infrastructure.cache;

import com.chronos.api.dto.AccountSummaryResponse;
import com.chronos.infrastructure.observability.ChronosMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChronosMetrics metrics;
    private final Duration ttl;

    public RedisCacheService(
        @Autowired(required = false) StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        @Autowired(required = false) ChronosMetrics metrics,
        @Value("${chronos.cache.account-summary-ttl:60s}") Duration ttl
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.metrics = metrics;
        this.ttl = ttl != null ? ttl : Duration.ofSeconds(60);
    }

    private String buildKey(UUID accountId) {
        return "chronos:account-summary:" + accountId.toString();
    }

    public Optional<AccountSummaryResponse> get(UUID accountId) {
        if (redisTemplate == null) {
            if (metrics != null) metrics.recordCacheMiss();
            return Optional.empty();
        }

        try {
            String key = buildKey(accountId);
            String json = redisTemplate.opsForValue().get(key);
            if (json != null && !json.isBlank()) {
                AccountSummaryResponse summary = objectMapper.readValue(json, AccountSummaryResponse.class);
                log.debug("Redis cache HIT for key {}", key);
                if (metrics != null) metrics.recordCacheHit();
                return Optional.of(summary);
            } else {
                log.debug("Redis cache MISS for key {}", key);
                if (metrics != null) metrics.recordCacheMiss();
                return Optional.empty();
            }
        } catch (Exception e) {
            log.warn("Redis cache read failure for account {}: {}. Falling back gracefully.", accountId, e.getMessage());
            if (metrics != null) metrics.recordCacheFailure();
            return Optional.empty();
        }
    }

    public void put(UUID accountId, AccountSummaryResponse summary) {
        if (redisTemplate == null || summary == null) {
            return;
        }

        try {
            String key = buildKey(accountId);
            String json = objectMapper.writeValueAsString(summary);
            redisTemplate.opsForValue().set(key, json, ttl);
            log.debug("Redis cache PUT for key {}", key);
        } catch (Exception e) {
            log.warn("Redis cache write failure for account {}: {}. Continuing without cache.", accountId, e.getMessage());
            if (metrics != null) metrics.recordCacheFailure();
        }
    }

    public void evict(UUID accountId) {
        if (redisTemplate == null) {
            return;
        }

        try {
            String key = buildKey(accountId);
            redisTemplate.delete(key);
            log.debug("Redis cache EVICT for key {}", key);
        } catch (Exception e) {
            log.warn("Redis cache evict failure for account {}: {}.", accountId, e.getMessage());
            if (metrics != null) metrics.recordCacheFailure();
        }
    }
}
