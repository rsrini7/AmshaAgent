package com.amsha.core.sandbox;

import com.amsha.core.exception.AmshaException;

public class PodmanUnavailableException extends AmshaException {
    public PodmanUnavailableException(String message) {
        super(message);
    }
}
