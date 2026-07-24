package com.thaap.payments.tenant;

import com.thaap.payments.risk.RiskOperatorContextArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class TenantWebMvcConfiguration implements WebMvcConfigurer {

    private final MerchantContextArgumentResolver merchantContextArgumentResolver;
    private final RiskOperatorContextArgumentResolver riskOperatorContextArgumentResolver;

    public TenantWebMvcConfiguration(MerchantContextArgumentResolver merchantContextArgumentResolver,
            RiskOperatorContextArgumentResolver riskOperatorContextArgumentResolver) {
        this.merchantContextArgumentResolver = merchantContextArgumentResolver;
        this.riskOperatorContextArgumentResolver = riskOperatorContextArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(merchantContextArgumentResolver);
        resolvers.add(riskOperatorContextArgumentResolver);
    }
}
