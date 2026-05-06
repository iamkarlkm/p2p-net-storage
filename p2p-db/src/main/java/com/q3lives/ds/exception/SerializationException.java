package com.q3lives.ds.exception;

/**
 * 序列化异常
 */
public class SerializationException extends RuntimeException {
    
    public SerializationException(String message) {
        super(message);
    }
    
    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
