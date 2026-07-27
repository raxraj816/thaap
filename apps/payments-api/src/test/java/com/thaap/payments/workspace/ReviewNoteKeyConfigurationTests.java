package com.thaap.payments.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class ReviewNoteKeyConfigurationTests {

    @Test
    void configurationRequiresCurrentAndEveryLiveCiphertextVersion() {
        ReviewNoteRepository repository = mock(ReviewNoteRepository.class);
        ReviewNoteCipher cipher = mock(ReviewNoteCipher.class);
        when(cipher.isConfigured()).thenReturn(true);
        when(cipher.canDecrypt("v1")).thenReturn(true);
        when(repository.findRequiredActiveKeyVersions()).thenReturn(List.of("v1"));

        new ReviewNoteKeyConfiguration(repository, cipher).validateRequiredKeys();

        when(repository.findRequiredActiveKeyVersions()).thenReturn(List.of("retired-v0"));
        assertThatThrownBy(() -> new ReviewNoteKeyConfiguration(repository, cipher)
                .validateRequiredKeys())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A live review note requires an unavailable key version");
        when(cipher.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> new ReviewNoteKeyConfiguration(repository, cipher)
                .validateRequiredKeys())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Review note current encryption key is unavailable");
    }

    @Test
    void healthFailsClosedWithoutLeakingKeyVersionOrMaterial() {
        ReviewNoteKeyConfiguration configuration = mock(ReviewNoteKeyConfiguration.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("secret key v9"))
                .when(configuration).validateRequiredKeys();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalystWorkspaceMetrics metrics = new AnalystWorkspaceMetrics(registry);
        AnalystWorkspaceHealthIndicator health =
                new AnalystWorkspaceHealthIndicator(configuration, metrics);

        org.springframework.boot.health.contributor.Health result = health.health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails()).isEmpty();
        assertThat(registry.get("thaap.analyst_workspace.readiness.failures")
                .tag("cause", "NOTE_KEY_UNAVAILABLE").counter().count()).isEqualTo(1.0d);
    }
}
