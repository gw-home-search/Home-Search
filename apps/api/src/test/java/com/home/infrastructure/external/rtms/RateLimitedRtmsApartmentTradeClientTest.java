package com.home.infrastructure.external.rtms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.home.application.ingest.trade.OpenApiTradeIngestBatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateLimitedRtmsApartmentTradeClientTest {

	private static final long NANOS_PER_MILLI = 1_000_000L;
	private static final RtmsApartmentTradeRequest REQUEST = new RtmsApartmentTradeRequest("11680", "202512", 1);

	private final OpenApiTradeIngestBatch batch =
		new OpenApiTradeIngestBatch("RTMS", "11680", "202512", 1, List.of());
	private final AtomicLong nowNanos = new AtomicLong(0);
	private final List<Long> sleptMillis = Collections.synchronizedList(new ArrayList<>());
	private final List<Long> delegateCallNanos = Collections.synchronizedList(new ArrayList<>());
	private final AtomicInteger delegateCalls = new AtomicInteger();

	@Test
	@DisplayName("첫 호출은 대기 없이 위임한다")
	void firstCallDelegatesWithoutWaiting() {
		RtmsApartmentTradeClient client = pacedClient(200);

		client.fetchPage(REQUEST);

		assertThat(sleptMillis).isEmpty();
		assertThat(delegateCalls.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("간격 내 재호출은 남은 간격만큼 대기한 뒤 위임한다")
	void callWithinIntervalWaitsForRemainingInterval() {
		RtmsApartmentTradeClient client = pacedClient(200);

		client.fetchPage(REQUEST);
		advanceMillis(50);
		client.fetchPage(REQUEST);

		assertThat(sleptMillis).containsExactly(150L);
		assertThat(delegateCalls.get()).isEqualTo(2);
	}

	@Test
	@DisplayName("간격이 지난 뒤 재호출은 대기하지 않는다")
	void callAfterIntervalDoesNotWait() {
		RtmsApartmentTradeClient client = pacedClient(200);

		client.fetchPage(REQUEST);
		advanceMillis(250);
		client.fetchPage(REQUEST);

		assertThat(sleptMillis).isEmpty();
		assertThat(delegateCalls.get()).isEqualTo(2);
	}

	@Test
	@DisplayName("간격 0은 pacing을 비활성화한다")
	void zeroIntervalDisablesPacing() {
		RtmsApartmentTradeClient client = pacedClient(0);

		client.fetchPage(REQUEST);
		client.fetchPage(REQUEST);
		client.fetchPage(REQUEST);

		assertThat(sleptMillis).isEmpty();
		assertThat(delegateCalls.get()).isEqualTo(3);
	}

	@Test
	@DisplayName("fetch 호출도 같은 간격 제어를 공유한다")
	void fetchSharesTheSamePacing() {
		RtmsApartmentTradeClient client = pacedClient(200);

		client.fetch(REQUEST);
		client.fetch(REQUEST);

		assertThat(sleptMillis).containsExactly(200L);
		assertThat(delegateCalls.get()).isEqualTo(2);
	}

	@Test
	@DisplayName("연속 호출은 호출마다 최소 간격을 보장한다")
	void consecutiveCallsKeepMinimumSpacingEachTime() {
		RtmsApartmentTradeClient client = pacedClient(200);

		client.fetchPage(REQUEST);
		client.fetchPage(REQUEST);
		client.fetchPage(REQUEST);

		assertThat(sleptMillis).containsExactly(200L, 200L);
		assertThat(delegateCalls.get()).isEqualTo(3);
	}

	@Test
	@DisplayName("1ms 미만 잔여 대기도 내림하지 않고 최소 간격을 보장한다")
	void fractionalRemainingWaitStillKeepsMinimumSpacing() {
		RtmsApartmentTradeClient client = pacedClient(200);

		client.fetchPage(REQUEST);
		advanceNanos(199 * NANOS_PER_MILLI + 500_000L);
		client.fetchPage(REQUEST);

		assertThat(sleptMillis).containsExactly(1L);
		assertThat(minDelegateCallGapNanos()).isGreaterThanOrEqualTo(200 * NANOS_PER_MILLI);
	}

	@Test
	@DisplayName("sleep이 요청보다 일찍 깨어나면 남은 간격을 재확인하고 다시 대기한다")
	void earlyWakeupRechecksRemainingInterval() {
		RtmsApartmentTradeClient client = new RateLimitedRtmsApartmentTradeClient(
			countingDelegate(),
			200,
			nowNanos::get,
			millis -> {
				sleptMillis.add(millis);
				advanceMillis(100);
			}
		);

		client.fetchPage(REQUEST);
		advanceMillis(50);
		client.fetchPage(REQUEST);

		assertThat(sleptMillis).containsExactly(150L, 50L);
		assertThat(minDelegateCallGapNanos()).isGreaterThanOrEqualTo(200 * NANOS_PER_MILLI);
	}

	@Test
	@DisplayName("동시 호출에서도 delegate 호출 시작 간격이 최소 간격 아래로 내려가지 않는다")
	void concurrentCallsKeepMinimumSpacingBetweenDelegateCallStarts() throws InterruptedException {
		RtmsApartmentTradeClient client = pacedClient(200);
		client.fetchPage(REQUEST);
		CountDownLatch start = new CountDownLatch(1);
		List<Thread> threads = List.of(
			pacedCallThread(client, start),
			pacedCallThread(client, start)
		);
		threads.forEach(Thread::start);

		start.countDown();
		for (Thread thread : threads) {
			thread.join(5_000L);
			assertThat(thread.isAlive()).isFalse();
		}

		assertThat(delegateCalls.get()).isEqualTo(3);
		assertThat(minDelegateCallGapNanos()).isGreaterThanOrEqualTo(200 * NANOS_PER_MILLI);
	}

	private static Thread pacedCallThread(RtmsApartmentTradeClient client, CountDownLatch start) {
		return new Thread(() -> {
			try {
				start.await();
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
			client.fetchPage(REQUEST);
		});
	}

	private long minDelegateCallGapNanos() {
		List<Long> callNanos = new ArrayList<>(delegateCallNanos);
		Collections.sort(callNanos);
		long minGap = Long.MAX_VALUE;
		for (int i = 1; i < callNanos.size(); i++) {
			minGap = Math.min(minGap, callNanos.get(i) - callNanos.get(i - 1));
		}
		return minGap;
	}

	private RtmsApartmentTradeClient pacedClient(long minIntervalMillis) {
		return new RateLimitedRtmsApartmentTradeClient(
			countingDelegate(),
			minIntervalMillis,
			nowNanos::get,
			millis -> {
				sleptMillis.add(millis);
				advanceMillis(millis);
			}
		);
	}

	private RtmsApartmentTradeClient countingDelegate() {
		return new RtmsApartmentTradeClient() {

			@Override
			public OpenApiTradeIngestBatch fetch(RtmsApartmentTradeRequest request) {
				recordCall();
				return batch;
			}

			@Override
			public RtmsApartmentTradePage fetchPage(RtmsApartmentTradeRequest request) {
				recordCall();
				return RtmsApartmentTradePage.single(batch);
			}

			private void recordCall() {
				delegateCalls.incrementAndGet();
				delegateCallNanos.add(nowNanos.get());
			}
		};
	}

	private void advanceMillis(long millis) {
		nowNanos.addAndGet(millis * NANOS_PER_MILLI);
	}

	private void advanceNanos(long nanos) {
		nowNanos.addAndGet(nanos);
	}
}
