package com.home.application.place;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

final class NearbyPlaceQueryExecutor {

    private final Executor executor;
    private final long timeoutMillis;

    NearbyPlaceQueryExecutor(NearbyPlaceExecutionOptions options) {
        NearbyPlaceExecutionOptions executionOptions = Objects.requireNonNull(options);
        this.executor = executionOptions.executor();
        this.timeoutMillis = executionOptions.totalTimeout().toMillis();
    }

    <T> T execute(Supplier<T> task) {
        return executeAll(List.of(task)).getFirst();
    }

    <T> List<T> executeAll(List<? extends Supplier<T>> tasks) {
        List<CompletableFuture<T>> futures = new ArrayList<>(tasks.size());
        try {
            for (Supplier<T> task : tasks) {
                futures.add(CompletableFuture.supplyAsync(task, executor));
            }
        } catch (RejectedExecutionException exception) {
            cancel(futures);
            throw new NearbyPlaceProviderUnavailableException("nearby place capacity unavailable", exception);
        }

        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
            return futures.stream().map(this::join).toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancel(futures);
            throw new NearbyPlaceProviderUnavailableException("nearby place query interrupted", exception);
        } catch (TimeoutException exception) {
            cancel(futures);
            throw new NearbyPlaceProviderUnavailableException("nearby place query timed out", exception);
        } catch (ExecutionException exception) {
            cancel(futures);
            throw providerFailure(exception.getCause());
        }
    }

    private <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (RuntimeException exception) {
            throw providerFailure(exception.getCause() == null ? exception : exception.getCause());
        }
    }

    private NearbyPlaceProviderUnavailableException providerFailure(Throwable cause) {
        if (cause instanceof NearbyPlaceProviderUnavailableException unavailable) {
            return unavailable;
        }
        return new NearbyPlaceProviderUnavailableException("nearby place provider unavailable", cause);
    }

    private void cancel(List<? extends CompletableFuture<?>> futures) {
        futures.forEach(future -> future.cancel(true));
    }
}
