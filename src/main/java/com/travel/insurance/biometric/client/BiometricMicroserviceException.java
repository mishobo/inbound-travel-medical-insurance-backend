package com.travel.insurance.biometric.client;

public class BiometricMicroserviceException extends RuntimeException {

    public BiometricMicroserviceException(String message) {
        super(message);
    }

    public BiometricMicroserviceException(String message, Throwable cause) {
        super(message, cause);
    }
}