package com.goodsup.demo.common.apiResponse;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomApiResponse<T>(
        int statusCode,
        T data,
        ErrorResult error,
        String message
) {

    public static <T> CustomApiResponse<T> success(T data, int statusCode, String message) {
        return new CustomApiResponse<>(statusCode, data, null, message);
    }

    public ResponseEntity<CustomApiResponse<T>> toResponseEntity() {
        return ResponseEntity.status(statusCode).body(this);
    }

    public static CustomApiResponse<?> fail(ErrorCode errorCode) {
        return new CustomApiResponse<>(
                errorCode.getStatusCode(),
                null,
                new ErrorResult(errorCode.getMessage(), null),
                null
        );
    }

    public static CustomApiResponse<?> fail(ErrorCode errorCode, List<String> details) {
        return new CustomApiResponse<>(
                errorCode.getStatusCode(),
                null,
                new ErrorResult(errorCode.getMessage(), details),
                null
        );
    }

    public static void errorResponse(HttpServletResponse response, String message, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String jsonResponse = String.format("{\"message\": \"%s\", \"status\": %d}", message, status);
        response.getWriter().write(jsonResponse);
    }

    private record ErrorResult(String message, List<String> details) {
    }
}
