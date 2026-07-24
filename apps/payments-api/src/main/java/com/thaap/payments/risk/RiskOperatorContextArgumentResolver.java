package com.thaap.payments.risk;

import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.security.SensitiveValueHasher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RiskOperatorContextArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String RISK_KEY_HEADER = "X-Risk-Operator-Key";

    private final RiskOperatorCredentialRepository credentialRepository;
    private final RiskOperatorRepository operatorRepository;
    private final MerchantRepository merchantRepository;
    private final SensitiveValueHasher sensitiveValueHasher;

    public RiskOperatorContextArgumentResolver(RiskOperatorCredentialRepository credentialRepository,
            RiskOperatorRepository operatorRepository, MerchantRepository merchantRepository,
            SensitiveValueHasher sensitiveValueHasher) {
        this.credentialRepository = credentialRepository;
        this.operatorRepository = operatorRepository;
        this.merchantRepository = merchantRepository;
        this.sensitiveValueHasher = sensitiveValueHasher;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return RiskOperatorContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String rawKey = request == null ? null : request.getHeader(RISK_KEY_HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Missing X-Risk-Operator-Key header");
        }
        List<String> identities = sensitiveValueHasher.hmacSha256Keyring(
                        "risk.operator.credential", rawKey)
                .stream().map(SensitiveValueHasher.VersionedDigest::digest).toList();
        List<RiskOperatorCredential> credentials = credentialRepository
                .findBySecretIdentityHmacInAndDisabledAtIsNull(identities);
        if (credentials.size() != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid risk operator credential");
        }
        RiskOperatorCredential credential = credentials.getFirst();
        RiskOperator operator = operatorRepository.findByIdAndMerchantId(
                        credential.getRiskOperatorId(), credential.getMerchantId())
                .filter(RiskOperator::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Invalid risk operator credential"));
        if (!merchantRepository.findById(operator.getMerchantId()).filter(merchant -> merchant.isActive()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid risk operator credential");
        }
        return new RiskOperatorContext(operator.getMerchantId(), operator.getId(), operator.getOperatorCode(),
                operator.getRole(), credential.getSecretPrefix());
    }
}
