package com.thaap.payments.tenant;

import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.security.SecretHasher;
import jakarta.servlet.http.HttpServletRequest;
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

    private final MerchantRepository merchantRepository;

    public MerchantContextArgumentResolver(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
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
        return merchantRepository.findByApiKeyHash(SecretHasher.sha256(apiKey))
                .filter(merchant -> merchant.isActive())
                .map(merchant -> new MerchantContext(merchant.getId(), merchant.getName(), merchant.getApiKeyPrefix()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key"));
    }
}
