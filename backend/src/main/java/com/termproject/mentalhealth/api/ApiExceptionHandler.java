package com.termproject.mentalhealth.api;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(Map.of("message", "입력값을 확인해 주세요."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> handleServerError(Exception error) {
        log.error("Unhandled API error", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "서버 처리 중 오류가 발생했습니다."));
    }
}
