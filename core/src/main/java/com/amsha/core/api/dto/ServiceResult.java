package com.amsha.core.api.dto;

public record ServiceResult<T>(T value, String errorCode, String errorMessage) {
    public boolean isSuccess() {
        return errorCode == null;
    }

    public static <T> ServiceResult<T> success(T value) {
        return new ServiceResult<>(value, null, null);
    }

    public static <T> ServiceResult<T> error(String code, String message) {
        return new ServiceResult<>(null, code, message);
    }
}
