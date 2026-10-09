package com.travel.planner.scheduler;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.repository.PlanRepository;
import com.travel.planner.service.PlanService;
import com.travel.planner.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class WeatherScheduler {

    private final PlanRepository planRepository;
    private final WeatherService weatherService;

    // 같은 계획·같은 날짜에 대해 3시간마다 같은 알림이 반복되지 않도록 기억해 둔다 (서버 재시작 시 초기화)
    private final Set<String> alreadyNotified = ConcurrentHashMap.newKeySet();

    @Scheduled(cron = "0 0 0/3 * * *")
    public void checkWeatherAndSendPush() {
        System.out.println("[기상 모니터링 데몬] 3시간 주기 악천후 검사 가동...");
        LocalDate today = LocalDate.now();

        // 출발이 오늘~모레인 계획만, 일정·장소·사용자를 한 번에 불러온다.
        // (이전에는 findAll() 후 트랜잭션 밖에서 plan.getItineraries() 를 읽어 LazyInitializationException 이 났다)
        List<Plan> activePlans = planRepository.findDetailByStartDateBetween(today, today.plusDays(2));

        for (Plan plan : activePlans) {
            String token = plan.getUser() != null ? plan.getUser().getFcmToken() : null;
            if (token == null || token.isBlank()) continue;

            String key = plan.getId() + ":" + plan.getStartDate();
            if (alreadyNotified.contains(key)) continue;

            // 더미(자유시간 등)나 공항이 아닌 첫 실제 장소의 좌표로 예보를 조회한다.
            Place target = null;
            for (Itinerary itinerary : plan.getItineraries()) {
                Place p = itinerary.getPlace();
                if (p == null || p.getLatitude() == null || p.getLongitude() == null) continue;
                if (PlanService.isPseudoCategory(p.getCategory()) || PlanService.isAirport(p)) continue;
                target = p;
                break;
            }
            if (target == null) continue;

            Map<LocalDate, Boolean> forecast = weatherService.getBadWeatherByDate(
                    target.getLatitude(), target.getLongitude(), plan.getStartDate(), plan.getStartDate());

            if (Boolean.TRUE.equals(forecast.get(plan.getStartDate()))) {
                String where = plan.getTitle() != null ? plan.getTitle() : "여행지";
                if (sendFcmPushAlert(token, "기상 악화 알림",
                        where + " 첫날에 비·눈 예보가 있습니다. 실내 일정으로 동선 재생성을 권장합니다!")) {
                    alreadyNotified.add(key);
                }
            }
        }
    }

    // 실제 구글 파이어베이스(FCM) 서버로 푸시 알림을 발송하는 로직
    private boolean sendFcmPushAlert(String targetToken, String title, String body) {
        if (FirebaseApp.getApps().isEmpty()) {
            return false;   // 서비스 계정 키가 없어 FCM 이 초기화되지 않은 환경
        }
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
            return true;
        } catch (Exception e) {
            System.err.println("[FCM 푸시 발송 실패]: " + e.getMessage());
            return false;
        }
    }
}
