package com.home.infrastructure.ops.notification;

public class NoopOpsNotifier implements OpsNotifier {

	@Override
	public void send(OpsNotification notification) {
		// no-op
	}
}
