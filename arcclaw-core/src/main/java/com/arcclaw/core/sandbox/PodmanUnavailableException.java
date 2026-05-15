package com.arcclaw.core.sandbox;

import com.arcclaw.core.exception.ArcClawException;

public class PodmanUnavailableException extends ArcClawException {
    public PodmanUnavailableException(String message) {
        super(message);
    }
}
