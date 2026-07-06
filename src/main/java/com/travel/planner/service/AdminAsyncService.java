package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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

        System.out.println("[어드민 엔진] 오프라인 AI 데이터 인리치먼트 백그라운드 파이프라인 가동...");
        java.util.Set<Long> skippedPlaceIds = new java.util.HashSet<>();

        while (isEnriching) {
            List<Place> allPlaces = placeRepository.findAll();
            List<Place> targetPlaces = allPlaces.stream()
                    .filter(p -> p.getTheme() == null || p.getTheme().trim().isEmpty()
                            || p.getPlaceType() == null || p.getPlaceType().trim().isEmpty())
                    .filter(p -> !skippedPlaceIds.contains(p.getId()))
                    .limit(30)
                    .collect(Collectors.toList());

            if (targetPlaces.isEmpty()) {
                System.out.println("[인리치먼트 완료 또는 잔여 스킵] 처리할 데이터가 없습니다!");
                isEnriching = false;
                break;
            }

            System.out.println("남은 테마 데이터 정제 중... (현재 " + targetPlaces.size() + "건 처리 시도)");
            int successCount = 0;
            boolean hit429 = false;

            for (Place place : targetPlaces) {
                if (!isEnriching) break;

                String reviewsText = googleMapsService.getPlaceReviews(place.getCity(), place.getName());
                String extractedData = aiService.classifyPlaceAttributes(place.getName(), reviewsText);

                if (extractedData == null || extractedData.trim().isEmpty()) {
                    System.out.println("[경고] AI 응답 없음 (429 제한 등). [" + place.getName() + "]에서 일시 정지합니다.");
                    hit429 = true;
                    break;
                }

                String[] parts = extractedData.split("\\|");

                if (parts.length >= 2) {
                    place.setTheme(parts[0].trim());
                    place.setPlaceType(parts[1].trim());
                    placeRepository.save(place);
                    successCount++;
                    System.out.println("[인리치먼트 성공] " + place.getName() + " -> 테마:[" + place.getTheme() + "], 속성:[" + place.getPlaceType() + "]");
                } else {
                    System.out.println("[형식 오류 스킵] AI 양식 위반: " + extractedData + " (DB 보존, 다음 턴에서 제외)");
                    skippedPlaceIds.add(place.getId());
                }

                try { Thread.sleep(3000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }

            if (hit429) {
                System.out.println("구글 API 한도 초기화를 위해 1분(60s) 동안 대기합니다...");
                try { Thread.sleep(60000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            } else {
                System.out.println("사이클 완료. 한도 누적 방지를 위해 10초간 안전 휴식을 취합니다...");
                try { Thread.sleep(10000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
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