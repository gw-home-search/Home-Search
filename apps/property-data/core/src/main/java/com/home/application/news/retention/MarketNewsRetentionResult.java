package com.home.application.news.retention;

public record MarketNewsRetentionResult(
        int rawItemsDeleted, int normalizedRowsDeleted, int executionRowsDeleted, int qualityRowsDeleted) {}
