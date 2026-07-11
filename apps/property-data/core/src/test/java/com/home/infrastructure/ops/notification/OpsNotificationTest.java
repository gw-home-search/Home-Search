package com.home.infrastructure.ops.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpsNotificationTest {

	@Test
	@DisplayName("ops notification은 eventType, title, message를 필수로 요구한다")
	void opsNotificationRequiresTextFields() {
		assertThatThrownBy(() -> new OpsNotification("", "title", "message"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("eventType");
		assertThatThrownBy(() -> new OpsNotification("event", "", "message"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("title");
		assertThatThrownBy(() -> new OpsNotification("event", "title", ""))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("message");
	}
}
