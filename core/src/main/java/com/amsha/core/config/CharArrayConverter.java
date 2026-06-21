package com.amsha.core.config;

import jakarta.annotation.Priority;

import org.eclipse.microprofile.config.spi.Converter;

@Priority(200)
public class CharArrayConverter implements Converter<char[]> {

    private static final long serialVersionUID = 1L;

    @Override
    public char[] convert(String value) {
        if (value == null) return new char[0];
        return value.toCharArray();
    }
}
