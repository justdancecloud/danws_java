package com.danws.protocol;

public class DanWSException extends RuntimeException {
    private final String code;

    public DanWSException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
