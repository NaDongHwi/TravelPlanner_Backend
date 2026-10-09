package com.travel.planner.controller;

import com.travel.planner.service.PlanGenerationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 잘못된 입력 → 400
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgumentException(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    // @Valid 검증 실패 → 400 + 어떤 필드가 왜 틀렸는지
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .collect(Collectors.joining(" "));
        return ResponseEntity.badRequest().body(message.isEmpty() ? "입력값을 확인해주세요." : message);
    }

    // 입력은 맞지만 그 조건으로 일정을 만들 수 없음 → 422 (이전에는 처리기가 없어 500 이었다)
    @ExceptionHandler(PlanGenerationException.class)
    public ResponseEntity<String> handlePlanGeneration(PlanGenerationException e) {
        return ResponseEntity.status(422).body(e.getMessage());
    }

    // 401(로그인 실패) / 403(남의 일정) / 404 / 409(중복 가입) 등 상태 코드를 지정해 던진 예외
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleResponseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(e.getReason());
    }
}
