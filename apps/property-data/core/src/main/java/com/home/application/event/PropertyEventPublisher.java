package com.home.application.event;

@FunctionalInterface
public interface PropertyEventPublisher {

    void publish(String topicName, String messageKey, String envelopeJson);
}
