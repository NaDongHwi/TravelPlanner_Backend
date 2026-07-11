package com.travel.planner.scheduler;

import com.travel.planner.entity.Plan;
import com.travel.planner.repository.PlanRepository;
import com.travel.planner.service.WeatherService;
import com.travel.planner.entity.Itinerary;
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

    @Scheduled(cron = "0 0 0/3 * * *")
    public void checkWeatherAndSendPush() {
        System.out.println("[기상 모니터링 데몬] 3시간 주기 악천후 검사 가동...");
        LocalDate today = LocalDate.now();

        List<Plan> activePlans = planRepository.findAll().stream()
                .filter(p -> !p.getStartDate().isBefore(today) && p.getStartDate().isBefore(today.plusDays(3)))
                .toList();

        for (Plan plan : activePlans) {
            List<Itinerary> itineraries = plan.getItineraries();

            // 방어 로직: 일정이 비어있거나 장소(Place) 정보가 없으면 스킵
            if (itineraries == null || itineraries.isEmpty() || itineraries.get(0).getPlace() == null) {
                continue;
            }

            // 사용자의 실제 첫 번째 방문 장소의 좌표를 추출
            double targetLat = itineraries.get(0).getPlace().getLatitude();
            double targetLon = itineraries.get(0).getPlace().getLongitude();

            // 위경도를 기반으로 미래 예보 조회
            String weather = weatherService.getForecastWeatherByCoords(targetLat, targetLon, plan.getStartDate());

            // 악천후 감지 로직 유지
            if (weather.contains("비") || weather.contains("눈") || weather.contains("폭우")) {
                if (plan.getUser().getFcmToken() != null) {
                    sendFcmPushAlert(plan.getUser().getFcmToken(),
                            "기상 악화 알림", plan.getInCity() + "에 악천후가 예상됩니다. 실내 일정으로 동선 재생성을 권장합니다!");
                }
            }
        }
    }

    private void sendFcmPushAlert(String targetToken, String title, String body) {
        System.out.println("[FCM 푸시 발송 성공] 대상: " + targetToken + " | 내용: " + body);
    }
}