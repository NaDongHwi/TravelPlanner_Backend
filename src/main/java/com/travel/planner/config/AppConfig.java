package com.travel.planner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {
    @Bean
    public RestTemplate restTemplate() {
        // 타임아웃이 없으면 외부 API(구글·Gemini·날씨)가 응답하지 않을 때 요청 스레드가 무한정 멈춘다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(60_000);   // Gemini 벌크 분류는 응답이 길어질 수 있다
        return new RestTemplate(factory);
    }
}
