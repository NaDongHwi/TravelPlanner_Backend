package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
public class Log {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String errorType; // 예: "AI_FAILOVER", "DB_ERROR"

    @Column(columnDefinition = "TEXT")
    private String errorMessage; // 에러 상세 내용

    @Column(nullable = false)
    private LocalDateTime errorTime = LocalDateTime.now(); // 에러 발생 시각
}