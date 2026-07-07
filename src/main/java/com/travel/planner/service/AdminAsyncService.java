package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminAsyncService {

    private final PlaceRepository placeRepository;
    private final GoogleMapsService googleMapsService;
    private final AiService aiService;

    private volatile boolean isEnriching = false;
    private volatile boolean isCleansing = false;

    public boolean isEnriching() { return isEnriching; }
    public void stopEnriching() { isEnriching = false; }

    public boolean isCleansing() { return isCleansing; }
    public void stopCleansing() { isCleansing = false; }

    @Async("adminTaskExecutor")
    public void runThemeEnrichment() {
        if (isEnriching) return;
        isEnriching = true;

        System.out.println("[어드민 엔진] 오프라인 AI 데이터 인리치먼트 백그라운드 파이프라인 가동 (벌크 최적화)...");

        while (isEnriching) {
            List<Place> allPlaces = placeRepository.findAll();
            List<Place> targetPlaces = allPlaces.stream()
                    .filter(p -> p.getTheme() == null || p.getTheme().trim().isEmpty()
                            || p.getPlaceType() == null || p.getPlaceType().trim().isEmpty())
                    .limit(30)
                    .collect(Collectors.toList());

            if (targetPlaces.isEmpty()) {
                System.out.println("[인리치먼트 완료] 처리할 데이터가 없습니다!");
                isEnriching = false;
                break;
            }

            System.out.println("남은 테마 데이터 정제 중... (현재 " + targetPlaces.size() + "건 일괄 처리 시도)");

            // 1. 구글 리뷰 30건 개별 수집
            Map<String, String> reviewsMap = new HashMap<>();
            List<Place> validPlacesForBulk = new ArrayList<>(); // 퀄리티가 검증된 장소만 모을 리스트

            for (Place p : targetPlaces) {
                if (!isEnriching) break;
                String reviewsText = googleMapsService.getPlaceReviews(p.getCity(), p.getName());

                // 🚀 [쓰레기 데이터 방어 로직] 통신 에러(null) 발생 시 이번 연산에서 제외!
                if (reviewsText == null) {
                    System.out.println("[" + p.getName() + "] 리뷰 수집 실패! 쓰레기 값 방지를 위해 이번 턴에서 제외합니다.");
                    continue; // AI에게 넘기지 않고 패스 (다음 사이클에서 재시도 됨)
                }

                reviewsMap.put(p.getPlaceId(), reviewsText);
                validPlacesForBulk.add(p); // 통과한 데이터만 진짜 리스트에 추가
            }
            if (!isEnriching) break;

            if (validPlacesForBulk.isEmpty()) {
                System.out.println("모든 리뷰 수집이 실패했습니다. 10초 대기 후 재시도합니다.");
                try { Thread.sleep(10000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                continue;
            }

            // 2. AI 벌크 분류 요청 (30건을 1번의 제미나이 호출로 처리)
            Map<String, String> enrichedDataMap = aiService.classifyPlaceAttributesBulk(targetPlaces, reviewsMap);

            // 3. AI 429 에러 방어
            if (enrichedDataMap == null || enrichedDataMap.isEmpty()) {
                System.out.println("[경고] AI API 429 한도 초과! 1분(60초) 대기 후 재시도합니다...");
                try { Thread.sleep(60000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                continue;
            }

            // 4. DB 일괄 업데이트
            int successCount = 0;
            for (Place place : targetPlaces) {
                String aiResult = enrichedDataMap.get(place.getPlaceId());
                if (aiResult != null && aiResult.contains("|")) {
                    String[] parts = aiResult.split("\\|");
                    if (parts.length >= 2) {
                        place.setTheme(parts[0].trim());
                        place.setPlaceType(parts[1].trim());
                        placeRepository.save(place);
                        successCount++;
                    }
                }
            }
            System.out.println("[벌크 인리치먼트 성공] 30건 중 " + successCount + "건 테마/속성 적재 완료.");

            // 5. 사이클 휴식 (1번 호출했으니 짧게 10초만 쉬어도 충분합니다)
            try {
                System.out.println("API 한도 누적 방지를 위해 10초간 안전 휴식을 취합니다...");
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("[테마 인리치먼트 종료] 백그라운드 스레드가 안전하게 정지되었습니다.");
    }

    @Async("adminTaskExecutor")
    public void runCategoryCleansing() {
        if (isCleansing) return;
        isCleansing = true;

        System.out.println("[자동 정제 시작] AI 카테고리 자동 분류를 시작합니다...");

        while (isCleansing) {
            List<Place> allPlaces = placeRepository.findAll();
            List<Place> targetPlaces = allPlaces.stream()
                    .filter(p -> p.getCategory() == null)
                    .limit(30)
                    .collect(Collectors.toList());

            if (targetPlaces.isEmpty()) {
                System.out.println("[자동 정제 완료] 모든 데이터의 카테고리 분류가 100% 완료되었습니다!");
                isCleansing = false;
                break;
            }

            System.out.println("남은 데이터 정제 중... (현재 30건 처리 시도)");
            Map<String, String> categorizedMap = aiService.cleansePlaceCategories(targetPlaces);

            if (categorizedMap == null || categorizedMap.isEmpty()) {
                System.out.println("[경고] AI API 요청 제한(429) 감지! 1분(60s) 동안 대기합니다...");
                try { Thread.sleep(60000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                continue;
            }

            int updateCount = 0;
            for (Place place : targetPlaces) {
                String newCategory = categorizedMap.get(place.getPlaceId());
                if (newCategory != null) {
                    place.setCategory(newCategory);
                    placeRepository.save(place);
                    updateCount++;
                }
            }
            System.out.println("30건 중 " + updateCount + "건 업데이트 완료.");

            try {
                System.out.println("API 한도 누적을 방지하기 위해 30초간 안전 휴식을 취합니다...");
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("[자동 정제 종료] 백그라운드 정제 스레드가 안전하게 정지되었습니다.");
    }
}