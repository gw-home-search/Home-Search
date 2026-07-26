package com.home.application.news.read;

public class InvalidNewsQueryException extends RuntimeException {

    public InvalidNewsQueryException(String message) {
        super(message);
    }
}
