package com.home.infrastructure.external.rtms;

import java.util.Objects;
import java.util.function.LongSupplier;

import com.home.application.ingest.trade.OpenApiTradeIngestBatch;

/**
 * 모든 RTMS open API 호출 사이에 최소 간격을 강제해 quota 소진과 호출 차단을 방지하는 client decorator입니다.
 * 간격 0은 pacing을 비활성화합니다. 간격이 설정되면 delegate 호출 자체를 직렬화해
 * 동시 호출에서도 실제 호출 시작 간격이 최소 간격 아래로 내려가지 않습니다.
 */
class RateLimitedRtmsApartmentTradeClient implements RtmsApartmentTradeClient {

	private static final long NANOS_PER_MILLI = 1_000_000L;

	private final RtmsApartmentTradeClient delegate;
	private final long minIntervalNanos;
	private final LongSupplier nanoTime;
	private final Sleeper sleeper;
	private long nextAllowedAtNanos;

	RateLimitedRtmsApartmentTradeClient(RtmsApartmentTradeClient delegate, long minIntervalMillis) {
		this(delegate, minIntervalMillis, System::nanoTime, Thread::sleep);
	}

	RateLimitedRtmsApartmentTradeClient(
		RtmsApartmentTradeClient delegate,
		long minIntervalMillis,
		LongSupplier nanoTime,
		Sleeper sleeper
	) {
		if (minIntervalMillis < 0) {
			throw new IllegalArgumentException("minIntervalMillis must not be negative");
		}
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.minIntervalNanos = minIntervalMillis * NANOS_PER_MILLI;
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
		this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
		this.nextAllowedAtNanos = nanoTime.getAsLong();
	}

	@Override
	public OpenApiTradeIngestBatch fetch(RtmsApartmentTradeRequest request) {
		if (minIntervalNanos == 0) {
			return delegate.fetch(request);
		}
		synchronized (this) {
			awaitTurnLocked();
			return delegate.fetch(request);
		}
	}

	@Override
	public RtmsApartmentTradePage fetchPage(RtmsApartmentTradeRequest request) {
		if (minIntervalNanos == 0) {
			return delegate.fetchPage(request);
		}
		synchronized (this) {
			awaitTurnLocked();
			return delegate.fetchPage(request);
		}
	}

	private void awaitTurnLocked() {
		long now = nanoTime.getAsLong();
		while (now < nextAllowedAtNanos) {
			sleepAtLeast(nextAllowedAtNanos - now);
			now = nanoTime.getAsLong();
		}
		nextAllowedAtNanos = now + minIntervalNanos;
	}

	private void sleepAtLeast(long remainingNanos) {
		long millis = (remainingNanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI;
		try {
			sleeper.sleep(millis);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while pacing RTMS open API call", exception);
		}
	}

	@FunctionalInterface
	interface Sleeper {

		void sleep(long millis) throws InterruptedException;
	}
}
