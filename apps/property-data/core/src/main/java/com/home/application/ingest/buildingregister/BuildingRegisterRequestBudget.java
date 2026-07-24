package com.home.application.ingest.buildingregister;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class BuildingRegisterRequestBudget {
    private final int maxRequests;
    private final AtomicInteger used = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public BuildingRegisterRequestBudget(int maxRequests) {
        if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be positive");
        this.maxRequests = maxRequests;
    }

    public void consume() {
        while (true) {
            int current = used.get();
            if (stopped.get() || current >= maxRequests)
                throw new BuildingRegisterRequestBudgetExceededException(current);
            if (used.compareAndSet(current, current + 1)) return;
        }
    }

    public int used() {
        return used.get();
    }

    public int remaining() {
        return Math.max(0, maxRequests - used());
    }

    public void stop() {
        stopped.set(true);
    }
}
