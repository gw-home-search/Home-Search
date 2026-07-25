package com.home.user.worker;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

public final class SequentialKafkaBackOff implements BackOff {

    private static final long[] DELAYS = {1_000, 5_000, 30_000};

    @Override
    public BackOffExecution start() {
        AtomicInteger attempt = new AtomicInteger();
        return () -> {
            int index = attempt.getAndIncrement();
            return index < DELAYS.length ? DELAYS[index] : BackOffExecution.STOP;
        };
    }
}
