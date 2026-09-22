package com.travel.insurance.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding contract for BiometricMicroserviceProperties.
 *
 * <p>Production binds relaxed property names via {@code @ConfigurationProperties}:
 * {@code app.biometrics.microservice.base-url} (or .baseUrl) and
 * {@code app.biometrics.microservice.webhook-secret} (or .webhookSecret) must
 * land on the {@code getBaseUrl()} / {@code getWebhookSecret()} accessors used
 * by BiometricMicroserviceClient and MicroserviceWebhookSignatureVerifier.
 */
class BiometricMicroservicePropertiesTest {

    @Test
    void bindsKebabDottedProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("app.biometrics.microservice.base-url", "http://biometric-ms:8090");
        source.put("app.biometrics.microservice.webhook-secret", "hook-secret-1");

        BiometricMicroserviceProperties properties = new Binder(source)
                .bind("app.biometrics.microservice",
                        Bindable.of(BiometricMicroserviceProperties.class))
                .get();

        assertThat(properties.getBaseUrl()).isEqualTo("http://biometric-ms:8090");
        assertThat(properties.getWebhookSecret()).isEqualTo("hook-secret-1");
    }

    @Test
    void bindsCamelCaseDottedProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource();
        source.put("app.biometrics.microservice.baseUrl", "http://biometric-ms:9090");
        source.put("app.biometrics.microservice.webhookSecret", "hook-secret-2");

        BiometricMicroserviceProperties properties = new Binder(source)
                .bind("app.biometrics.microservice",
                        Bindable.of(BiometricMicroserviceProperties.class))
                .get();

        assertThat(properties.getBaseUrl()).isEqualTo("http://biometric-ms:9090");
        assertThat(properties.getWebhookSecret()).isEqualTo("hook-secret-2");
    }
}
