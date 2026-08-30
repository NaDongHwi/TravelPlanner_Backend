package com.travel.planner.scheduler;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.travel.planner.entity.Itinerary;
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

    @Scheduled(cron = "0 0 0/3 * * *")
    public void checkWeatherAndSendPush() {
        System.out.println("[기상 모니터링 데몬] 3시간 주기 악천후 검사 가동...");
        LocalDate today = LocalDate.now();

        List<Plan> activePlans = planRepository.findAll().stream()
                .filter(p -> !p.getStartDate().isBefore(today) && p.getStartDate().isBefore(today.plusDays(3)))
                .toList();

        for (Plan plan : activePlans) {
            List<Itinerary> itineraries = plan.getItineraries();

            if (itineraries == null || itineraries.isEmpty() || itineraries.get(0).getPlace() == null) {
                continue;
            }

            double targetLat = itineraries.get(0).getPlace().getLatitude();
            double targetLon = itineraries.get(0).getPlace().getLongitude();

            String weather = weatherService.getForecastWeatherByCoords(targetLat, targetLon, plan.getStartDate());

            if (weather.contains("비") || weather.contains("눈") || weather.contains("폭우")) {
                if (plan.getUser().getFcmToken() != null) {
                    sendFcmPushAlert(plan.getUser().getFcmToken(),
                            "기상 악화 알림", plan.getInCity() + "에 악천후가 예상됩니다. 실내 일정으로 동선 재생성을 권장합니다!");
                }
            }
        }
    }

    // 실제 구글 파이어베이스(FCM) 서버로 푸시 알림을 발송하는 로직
    private void sendFcmPushAlert(String targetToken, String title, String body) {
        try {
            Message message = Message.builder()
                    .setToken(targetToken)
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .build();

            // FirebaseMessaging 인스턴스를 통해 전송
            String response = FirebaseMessaging.getInstance().send(message);
            System.out.println("[FCM 푸시 발송 성공] 구글 서버 응답: " + response);
        } catch (Exception e) {
            System.err.println("[FCM 푸시 발송 실패]: " + e.getMessage());
        }
    }
}