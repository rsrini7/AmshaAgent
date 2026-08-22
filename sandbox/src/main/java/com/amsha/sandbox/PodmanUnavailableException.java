package com.amsha.sandbox;

import com.amsha.common.exception.AmshaException;

public class PodmanUnavailableException extends AmshaException {
    public PodmanUnavailableException(String message) {
        super(message);
    }
}
