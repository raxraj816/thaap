package com.thaap.payments.developer;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler that rejects a merchant credential presented without the scenario
 * runner token.
 *
 * <p>Most merchant operations merely <em>accept</em> the runner token: {@code
 * MerchantContextArgumentResolver} validates it when present and flips {@code
 * TrafficOrigin} to {@code SYNTHETIC_SCENARIO}. The handlers marked here are different —
 * they call {@code requireScenarioRunner} and fail closed without it, so for them a
 * merchant key alone genuinely is insufficient.
 *
 * <p>The distinction has to be declared somewhere a machine can read, because it lives in
 * a method body. Without it the published contract and the credential-boundary test can
 * only generalise over the path prefix, and both were wrong in opposite directions until
 * {@code ER-0098}. {@code PublicOpenApiConfiguration} reads this annotation to publish the
 * {@code scenarioRunner} security requirement, and {@code OpenApiSecurityContractTests}
 * reads it to derive the enforced boundary — so the contract is generated from the
 * enforcement point rather than from a list kept in step by hand.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresScenarioRunner {
}
