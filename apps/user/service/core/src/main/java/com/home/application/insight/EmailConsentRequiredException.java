package com.home.application.insight;

public final class EmailConsentRequiredException extends RuntimeException {
    public EmailConsentRequiredException() {
        super("A current account email and explicit consent are required.");
    }
}
