package com.travel.insurance.biometric.client;

/**
 * Raised when the legacy eKYC HTTP client cannot serialise, reach or parse a
 * verification request/response from the upstream eKYC identity provider.
 *
 * <p>Carried up to {@code GlobalExceptionHandler} where it maps to
 * {@code 502 Bad Gateway} (upstream identity-service failure), distinct from
 * {@link BiometricMicroserviceException} (signed-webhook HMAC failure →
 * {@code 401 Unauthorized}).
 */
public class EkYcClientException extends RuntimeException {

    public EkYcClientException(String message) {
        super(message);
    }

    public EkYcClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
