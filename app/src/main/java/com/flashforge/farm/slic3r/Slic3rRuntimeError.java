package com.flashforge.farm.slic3r;

public class Slic3rRuntimeError extends Exception {
    public Slic3rRuntimeError() {
    }

    public Slic3rRuntimeError(String message) {
        super(message);
    }

    public Slic3rRuntimeError(String message, Throwable cause) {
        super(message, cause);
    }

    public Slic3rRuntimeError(Throwable cause) {
        super(cause);
    }
}
