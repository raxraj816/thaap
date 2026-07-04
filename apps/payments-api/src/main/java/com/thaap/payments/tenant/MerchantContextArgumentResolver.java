package com.thaap.payments.tenant;

import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.security.SecretHasher;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MerchantContextArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final String SCENARIO_RUNNER_TOKEN_HEADER = "X-Thaap-Scenario-Runner-Token";

    private final MerchantRepository merchantRepository;
    private final String scenarioRunnerToken;

    public MerchantContextArgumentResolver(MerchantRepository merchantRepository,
            @Value("${thaap.scenario-runner.token:}") String scenarioRunnerToken) {
        this.merchantRepository = merchantRepository;
        this.scenarioRunnerToken = scenarioRunnerToken;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return MerchantContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String apiKey = request == null ? null : request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing X-Api-Key header");
        }
        TrafficOrigin trafficOrigin = resolveTrafficOrigin(request);
        return merchantRepository.findByApiKeyHash(SecretHasher.sha256(apiKey))
                .filter(merchant -> merchant.isActive())
                .map(merchant -> new MerchantContext(merchant.getId(), merchant.getName(),
                        merchant.getApiKeyPrefix(), trafficOrigin))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key"));
    }

    private TrafficOrigin resolveTrafficOrigin(HttpServletRequest request) {
        String suppliedToken = request == null ? null : request.getHeader(SCENARIO_RUNNER_TOKEN_HEADER);
        if (suppliedToken == null || suppliedToken.isBlank()) {
            return TrafficOrigin.MERCHANT_API;
        }
        if (scenarioRunnerToken.isBlank() || !MessageDigest.isEqual(
                suppliedToken.getBytes(StandardCharsets.UTF_8),
                scenarioRunnerToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid scenario runner credential");
        }
        return TrafficOrigin.SYNTHETIC_SCENARIO;
    }
}
