package com.mopl.content.external.tmdb;

public class TmdbApiException extends RuntimeException {

    public TmdbApiException(String message, Throwable cause) {
        super(message, cause);
    }
}