package com.travel.planner.service;

/** 입력은 올바르지만 그 조건으로는 일정을 만들 수 없을 때 (HTTP 422 로 응답) */
public class PlanGenerationException extends RuntimeException {
    public PlanGenerationException(String message) {
        super(message);
    }
}
