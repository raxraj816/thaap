package com.thaap.payments.velocity;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RedisVelocityService {

    private final StringRedisTemplate redisTemplate;
    private final VelocityScriptContract contract;
    private final VelocityCompensationScriptContract compensationContract;
    private final DefaultRedisScript<List> script;
    private final DefaultRedisScript<String> compensationScript;

    @SuppressWarnings("rawtypes")
    public RedisVelocityService(StringRedisTemplate redisTemplate, VelocityScriptContract contract,
            VelocityCompensationScriptContract compensationContract) {
        this.redisTemplate = redisTemplate;
        this.contract = contract;
        this.compensationContract = compensationContract;
        this.script = new DefaultRedisScript<>(contract.scriptText(), List.class);
        this.compensationScript = new DefaultRedisScript<>(compensationContract.scriptText(), String.class);
    }

    public VelocityObservation observe(String redisKey, String member, String reservationToken, Duration window,
            Duration ttl, int maxMembers) {
        requireSafeKey(redisKey);
        requireMember(member);
        requireReservationToken(reservationToken);
        long windowMs = boundedMillis(window, "window", 1_000L, 86_400_000L);
        long ttlMs = boundedMillis(ttl, "ttl", windowMs, 86_400_000L);
        if (maxMembers < 1 || maxMembers > 100_000) {
            throw new IllegalArgumentException("Velocity maximum members must be between 1 and 100000");
        }
        try {
            Object result = redisTemplate.execute(script, List.of(redisKey, ownerKey(redisKey, member)),
                    VelocityScriptContract.VERSION, member, Long.toString(windowMs), Long.toString(ttlMs),
                    Integer.toString(maxMembers), reservationToken);
            return contract.parse(result);
        } catch (VelocityUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VelocityUnavailableException(VelocityUnavailableException.Cause.DEPENDENCY_FAILURE,
                    "Velocity dependency operation failed");
        }
    }

    public VelocityCompensationResult compensate(String redisKey, String member, String reservationToken) {
        requireSafeKey(redisKey);
        requireMember(member);
        requireReservationToken(reservationToken);
        try {
            String result = redisTemplate.execute(compensationScript,
                    List.of(redisKey, ownerKey(redisKey, member)),
                    VelocityCompensationScriptContract.VERSION, member, reservationToken);
            return compensationContract.parse(result);
        } catch (VelocityUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VelocityUnavailableException(VelocityUnavailableException.Cause.DEPENDENCY_FAILURE,
                    "Velocity compensation dependency operation failed");
        }
    }

    private void requireSafeKey(String redisKey) {
        if (redisKey == null || !redisKey.matches("thaap:velocity:v1:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Velocity Redis key has invalid grammar");
        }
    }

    private void requireMember(String member) {
        if (member == null || !member.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Velocity member must be a 64-character lowercase digest");
        }
    }

    private void requireReservationToken(String reservationToken) {
        if (reservationToken == null || !reservationToken.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Velocity reservation token must be a 32-character lowercase digest");
        }
    }

    private String ownerKey(String redisKey, String member) {
        return "thaap:velocity:v1:owner:" + redisKey.substring(redisKey.lastIndexOf(':') + 1) + ":" + member;
    }

    private long boundedMillis(Duration duration, String field, long minimum, long maximum) {
        if (duration == null) {
            throw new IllegalArgumentException("Velocity " + field + " is required");
        }
        long value;
        try {
            value = duration.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Velocity " + field + " is outside the supported range", exception);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("Velocity " + field + " is outside the supported range");
        }
        return value;
    }
}
