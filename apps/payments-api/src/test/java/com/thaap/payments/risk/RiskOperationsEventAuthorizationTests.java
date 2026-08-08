package com.thaap.payments.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.thaap.payments.event.DomainEventActorType;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.platform.PlatformOperatorContext;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proof for {@code C46-AUTH-001}.
 *
 * <p>The row promised this suite and it was never written ({@code TD-0007}).
 *
 * <p>The row's invariant is that event insertion grants no new permission: existing role and
 * dual-control rules stay authoritative, and no event-specific mutation surface appears beside
 * them. That is a structural claim about the producer boundary, so it is proven structurally and
 * exhaustively rather than by sampling one endpoint. The strongest form of "no new permission" is
 * that there is no way to reach the producer without an already-authenticated typed context, and
 * no way to hand it an actor identity of your own choosing — both asserted here over every public
 * method the producer exposes, so a new method cannot quietly opt out.
 *
 * <p>The role matrix itself is not re-proven here. Each operational mutation is guarded by its own
 * service, and those guards have their own suites; this row's gap was that nothing checked the
 * event boundary did not bypass them.
 */
class RiskOperationsEventAuthorizationTests {

    private static final UUID MERCHANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000203");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-28T08:00:00Z");

    /**
     * Every public producer method must take its authority from a typed authenticated context or
     * be an explicitly system-attributed fact. A method taking a bare actor identity would let a
     * caller assert authorship the authenticator never granted.
     */
    @Test
    void everyProducerMethodTakesAnAuthenticatedContextOrIsAnExplicitSystemFact() {
        List<Method> publicMethods = Arrays.stream(
                        RiskOperationsEventProducer.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .toList();
        assertThat(publicMethods).isNotEmpty();

        for (Method method : publicMethods) {
            Class<?> first = method.getParameterTypes()[0];
            boolean authenticated = first == RiskOperatorContext.class
                    || first == MerchantContext.class;
            boolean systemFact = first == UUID.class
                    && method.getName().toLowerCase().contains("system")
                    || isSystemAttributed(method.getName());
            assertThat(authenticated || systemFact)
                    .describedAs("%s takes neither an authenticated context nor a system-fact "
                            + "shape, so its authority is unaccounted for", method.getName())
                    .isTrue();
        }
    }

    /**
     * Platform data authority must not cross into merchant risk operations. There is no producer
     * method that accepts a platform context, so the crossing is unreachable rather than merely
     * refused at runtime.
     */
    @Test
    void noProducerMethodAcceptsPlatformDataAuthority() {
        assertThat(Arrays.stream(RiskOperationsEventProducer.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .map(Class::getName))
                .doesNotContain(PlatformOperatorContext.class.getName());
    }

    /**
     * The producer is not a web surface. The row's implementation line says no event-specific
     * public mutation endpoint is added, and a controller annotation here is exactly how one
     * would appear.
     */
    @Test
    void theProducerIsNotAWebSurface() {
        assertThat(RiskOperationsEventProducer.class.getAnnotation(RestController.class)).isNull();
        assertThat(RiskOperationsEventProducer.class.getAnnotation(Controller.class)).isNull();
        assertThat(RiskOperationsEventProducer.class.getAnnotation(RequestMapping.class)).isNull();
        assertThat(Arrays.stream(RiskOperationsEventProducer.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getAnnotations()))
                .map(annotation -> annotation.annotationType().getPackageName()))
                .doesNotContain("org.springframework.web.bind.annotation");
    }

    /**
     * An operator-attributed fact records the authenticated operator as its actor. The identity
     * comes from the context the authenticator produced, so a caller cannot substitute another
     * operator's authorship by passing different arguments.
     */
    @Test
    void anOperatorFactRecordsTheAuthenticatedOperatorAsItsActor() {
        DomainEventWriter writer = mock(DomainEventWriter.class);
        RiskOperationsEventProducer producer = new RiskOperationsEventProducer(writer);
        RiskOperatorContext context = new RiskOperatorContext(MERCHANT_ID, OPERATOR_ID,
                "rop_operator_code", RiskOperatorRole.RISK_MANAGER, "rop_live_a");

        producer.manualReviewResolved(context, PAYMENT_ID, OCCURRED_AT, "auth-request",
                "CONFIRMED_FRAUD", "STOLEN_PAYMENT_CREDENTIAL", TrafficOrigin.MERCHANT_API);

        ArgumentCaptor<DomainEventCommand> captured =
                ArgumentCaptor.forClass(DomainEventCommand.class);
        verify(writer).append(captured.capture());
        DomainEventCommand command = captured.getValue();
        assertThat(command.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(command.actor().type()).isEqualTo(DomainEventActorType.RISK_OPERATOR);
        assertThat(command.actor().id()).isEqualTo(OPERATOR_ID.toString());
    }

    /**
     * A system-attributed fact carries a fixed system actor rather than borrowing whichever
     * operator happened to be nearby. Attributing an automatic transition to a person is how an
     * audit trail stops meaning anything.
     */
    @Test
    void aSystemFactCarriesAFixedSystemActorRatherThanAnOperator() {
        DomainEventWriter writer = mock(DomainEventWriter.class);
        RiskOperationsEventProducer producer = new RiskOperationsEventProducer(writer);

        producer.manualReviewOpenedBySystem(MERCHANT_ID, PAYMENT_ID, OCCURRED_AT,
                "auth-system-request", TrafficOrigin.MERCHANT_API, "HIGH", List.of("RULE_MATCH"));

        ArgumentCaptor<DomainEventCommand> captured =
                ArgumentCaptor.forClass(DomainEventCommand.class);
        verify(writer).append(captured.capture());
        assertThat(captured.getValue().actor().type())
                .isEqualTo(DomainEventActorType.SYSTEM);
        assertThat(captured.getValue().actor().id()).isEqualTo("thaap-risk-operations");
        assertThat(captured.getValue().actor().id())
                .doesNotContain(OPERATOR_ID.toString());
    }

    /** Producer methods whose first parameter is a merchant identity rather than a context. */
    private boolean isSystemAttributed(String methodName) {
        return List.of("manualReviewOpenedBySystem", "disputeWorkflowExpired",
                "webhookDeliveryExhausted", "webhookRedeliverySucceeded").contains(methodName);
    }
}
