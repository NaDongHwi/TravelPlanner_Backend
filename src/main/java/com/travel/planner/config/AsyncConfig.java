package com.travel.planner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync // 비동기 기능 켜기
public class AsyncConfig {

    @Bean(name = "adminTaskExecutor") // 이 그룹의 이름표
    public Executor threadPoolTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);  // 기본으로 대기하고 있는 스레드 수
        executor.setMaxPoolSize(5);   // 아무리 바빠도 최대 5개까지만 스레드 생성 (서버 폭발 방지)
        executor.setQueueCapacity(50); // 스레드 5개가 다 일하고 있으면, 최대 50개의 작업까지 대기열에 보관
        executor.setThreadNamePrefix("AdminAsync-"); // 로그에 찍힐 스레드 이름 (예: AdminAsync-1)
        executor.initialize();
        return executor;
    }
}