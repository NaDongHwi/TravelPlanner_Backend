package com.travel.planner.scheduler;

import com.travel.planner.entity.Plan;
import com.travel.planner.repository.PlanRepository;
import com.travel.planner.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class WeatherScheduler {

    private final PlanRepository planRepository;
    private final WeatherService weatherService;

    // 0 0 0/3 * * * = 매일 0시부터 3시간 간격으로 실행
    @Scheduled(cron = "0 0 0/3 * * *")
    public void checkWeatherAndSendPush() {
        System.out.println("[기상 모니터링 데몬] 3시간 주기 악천후 검사 가동...");
        LocalDate today = LocalDate.now();

        // 현재 여행 중이거나 3일 내로 출국하는 유저의 일정만 필터링
        List<Plan> activePlans = planRepository.findAll().stream()
                .filter(p -> !p.getStartDate().isBefore(today) && p.getStartDate().isBefore(today.plusDays(3)))
                .toList();

        for (Plan plan : activePlans) {
            String weather = weatherService.getCurrentWeather(plan.getInCity());

            // 악천후(비, 눈, 태풍) 키워드 감지 시 푸시 알림 발송
            if (weather.contains("비") || weather.contains("눈") || weather.contains("폭우")) {
                if (plan.getUser().getFcmToken() != null) {
                    sendFcmPushAlert(plan.getUser().getFcmToken(),
                            "기상 악화 알림", plan.getInCity() + "에 악천후가 예상됩니다. 실내 일정으로 동선 재생성을 권장합니다!");
                }
            }
        }
    }

    private void sendFcmPushAlert(String targetToken, String title, String body) {
        // 실제 FCM 서버로 HTTP POST 요청을 쏘는 로직 (현재는 콘솔 로그로 대체)
        System.out.println("[FCM 푸시 발송 성공] 대상: " + targetToken + " | 내용: " + body);
    }
}