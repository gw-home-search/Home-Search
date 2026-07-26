package com.home.application.news.collection;

public class RawNewsPositionConflictException extends RuntimeException {

    public RawNewsPositionConflictException() {
        super("provider position payload does not match saved raw evidence");
    }
}
