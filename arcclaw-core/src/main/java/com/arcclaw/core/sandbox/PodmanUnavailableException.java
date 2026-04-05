package com.arcclaw.core.sandbox;

import java.io.IOException;

public class PodmanUnavailableException extends IOException {
    public PodmanUnavailableException(String message) {
        super(message);
    }
}
