package com.home.application.news.collection;

public final class NewsCallBudgetExceededException extends RuntimeException {

    public NewsCallBudgetExceededException() {
        super("market news daily call budget exhausted");
    }
}
