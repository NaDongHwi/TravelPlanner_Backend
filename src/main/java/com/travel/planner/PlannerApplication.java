package com.travel.planner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // 스케줄러 기능 활성화
public class PlannerApplication {
	public static void main(String[] args) {
		SpringApplication.run(PlannerApplication.class, args);
	}
}