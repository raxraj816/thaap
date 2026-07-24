package com.thaap.payments.velocity;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component("velocityRedis")
public class VelocityRedisHealthIndicator implements HealthIndicator {

    private final boolean enabled;
    private final StringRedisTemplate redisTemplate;
    private final VelocityScriptContract contract;
    private final VelocityCompensationScriptContract compensationContract;

    public VelocityRedisHealthIndicator(@Value("${thaap.fraud.velocity.enabled:true}") boolean enabled,
            StringRedisTemplate redisTemplate, VelocityScriptContract contract,
            VelocityCompensationScriptContract compensationContract) {
        this.enabled = enabled;
        this.redisTemplate = redisTemplate;
        this.contract = contract;
        this.compensationContract = compensationContract;
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.up().withDetail("mode", "DISABLED").build();
        }
        try {
            Readiness readiness = redisTemplate.execute((RedisCallback<Readiness>) connection -> {
                String ping = connection.commands().ping();
                String loadedSha = connection.scriptingCommands().scriptLoad(
                        contract.scriptText().getBytes(StandardCharsets.UTF_8));
                String compensationSha = connection.scriptingCommands().scriptLoad(
                        compensationContract.scriptText().getBytes(StandardCharsets.UTF_8));
                return new Readiness(ping, loadedSha, compensationSha);
            });
            if (readiness == null || !"PONG".equals(readiness.ping())
                    || !VelocityScriptContract.SHA1.equals(readiness.loadedSha())
                    || !VelocityCompensationScriptContract.SHA1.equals(readiness.compensationSha())) {
                return Health.down().withDetail("cause", "SCRIPT_READINESS_MISMATCH").build();
            }
            return Health.up().withDetail("scriptVersion", VelocityScriptContract.VERSION)
                    .withDetail("scriptSha", VelocityScriptContract.SHA1)
                    .withDetail("compensationScriptVersion", VelocityCompensationScriptContract.VERSION)
                    .withDetail("compensationScriptSha", VelocityCompensationScriptContract.SHA1).build();
        } catch (RuntimeException exception) {
            return Health.down().withDetail("cause", "DEPENDENCY_UNAVAILABLE").build();
        }
    }

    private record Readiness(String ping, String loadedSha, String compensationSha) { }
}
