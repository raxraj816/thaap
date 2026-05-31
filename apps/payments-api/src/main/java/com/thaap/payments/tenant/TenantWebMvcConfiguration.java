package com.thaap.payments.tenant;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class TenantWebMvcConfiguration implements WebMvcConfigurer {

    private final MerchantContextArgumentResolver merchantContextArgumentResolver;

    public TenantWebMvcConfiguration(MerchantContextArgumentResolver merchantContextArgumentResolver) {
        this.merchantContextArgumentResolver = merchantContextArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(merchantContextArgumentResolver);
    }
}
