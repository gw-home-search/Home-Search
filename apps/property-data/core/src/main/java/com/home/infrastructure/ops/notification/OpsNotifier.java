package com.home.infrastructure.ops.notification;

@FunctionalInterface
public interface OpsNotifier {

    void send(OpsNotification notification);
}
