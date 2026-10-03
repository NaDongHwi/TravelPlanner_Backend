package com.travel.planner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        // 이번 실행 세션 동안에만 실패한 장소 ID를 기억하는 '임시 블랙리스트'
        // DB에 저장되지 않으므로, 작업 종료 후 다음에 다시 버튼을 누르면 초기화되어 재시도합니다.
        Set<Long> failedPlaceIdsThisSession = new HashSet<>();

        while (isEnriching) {
            List<Place> allPlaces = placeRepository.findAll();
            List<Place> targetPlaces = allPlaces.stream()
                    // 이번 세션에서 실패한 전적이 있는 장소는 필터링에서 제외 (무한 루프 방어)
                    .filter(p -> !failedPlaceIdsThisSession.contains(p.getId()))
                    .filter(p -> p.getTheme() == null || p.getTheme().trim().isEmpty()
                            || p.getPlaceType() == null || p.getPlaceType().trim().isEmpty()
                            || p.getRecommendedDuration() == null
                            || p.getOpeningHours() == null || p.getOpeningHours().contains("없음") || p.getOpeningHours().isEmpty())
                    .limit(30)
                    .collect(Collectors.toList());

            if (targetPlaces.isEmpty()) {
                System.out.println("[인리치먼트 완료] 남은 데이터가 없거나 모두 처리에 실패한 데이터입니다.");
                isEnriching = false;
                break;
            }

            System.out.println("남은 테마 데이터 정제 중... (현재 " + targetPlaces.size() + "건 일괄 처리 시도)");

            Map<String, String> reviewsMap = new HashMap<>();
            List<Place> validPlacesForBulk = new ArrayList<>();

            for (Place p : targetPlaces) {
                if (!isEnriching) break;

                // 빨간 줄의 원인이었던 변수 선언입니다. 기본적으로 성공한다고 가정합니다.
                boolean isOpeningHoursFixed = true;

                // 1. 영업시간 복구 로직
                if (p.getOpeningHours() == null || p.getOpeningHours().contains("없음") || p.getOpeningHours().isEmpty()) {
                    // 이름(Text Search) 대신 고유 ID(Place ID)로 다이렉트 호출
                    Place details = googleMapsService.getPlaceDetailsById(p.getPlaceId(), "ko");
                    if (details.getOpeningHours() != null && !details.getOpeningHours().contains("없음")) {
                        p.setOpeningHours(details.getOpeningHours());
                        placeRepository.save(p);
                        System.out.println("-> [" + p.getName() + "] 고유 ID 기반 영업시간 복구 완료!");
                        isOpeningHoursFixed = true; // 복구 성공
                    } else {
                        isOpeningHoursFixed = false; // 복구 실패 (해당 장소가 정말로 24시간 개방 거리/자연명소라 영업시간이 없는 경우 등)
                    }
                }

                // 2. 테마와 속성이 이미 있다면, AI 호출 스킵 로직
                if (p.getTheme() != null && p.getPlaceType() != null && p.getRecommendedDuration() != null) {
                    // 영업시간 복구에 실패했는데 테마만 완벽한 상태라면, 이 장소를 임시 블랙리스트에 올립니다.
                    if (!isOpeningHoursFixed) {
                        failedPlaceIdsThisSession.add(p.getId());
                    }
                    continue; // AI 호출 안 하고 스킵
                }

                // 3. 리뷰 수집
                String reviewsText = googleMapsService.getPlaceReviews(p.getCity(), p.getName());

                // 리뷰 수집이 불가능한 경우 (더미 데이터 삽입 대신 메모리에만 기록)
                if (reviewsText == null || reviewsText.contains("리뷰 정보 없음") || reviewsText.trim().isEmpty()) {
                    System.out.println("[" + p.getName() + "] 리뷰 수집 불가! 이번 세션에서 임시 제외합니다.");
                    failedPlaceIdsThisSession.add(p.getId()); // 임시 블랙리스트에 추가하여 다음 루프 때 배제됨
                    continue;
                }

                reviewsMap.put(p.getPlaceId(), reviewsText);
                validPlacesForBulk.add(p);
            }
            if (!isEnriching) break;

            if (validPlacesForBulk.isEmpty()) {
                System.out.println("[알림] 이번 30건은 AI 처리가 필요 없거나 리뷰가 없습니다. 10초 후 다음 배치를 탐색합니다.");
                try { Thread.sleep(10000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                continue;
            }

            // 4. AI 벌크 분류 요청
            Map<String, String> enrichedDataMap = aiService.classifyPlaceAttributesBulk(validPlacesForBulk, reviewsMap);

            if (enrichedDataMap == null || enrichedDataMap.isEmpty()) {
                System.out.println("[경고] AI API 429 한도 초과! 1분(60초) 대기 후 재시도합니다...");
                try { Thread.sleep(60000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                continue;
            }

            int successCount = 0;
            for (Place place : validPlacesForBulk) {
                String aiResult = enrichedDataMap.get(place.getPlaceId());
                if (aiResult != null && aiResult.contains("|")) {
                    String[] parts = aiResult.split("\\|");
                    if (parts.length >= 3) {
                        place.setTheme(parts[0].trim());
                        place.setPlaceType(parts[1].trim());
                        try {
                            place.setRecommendedDuration(Integer.parseInt(parts[2].trim()));
                        } catch (NumberFormatException e) {
                            place.setRecommendedDuration(90);
                        }
                        placeRepository.save(place);
                        successCount++;
                    } else {
                        failedPlaceIdsThisSession.add(place.getId()); // AI 응답 형식이 깨진 것도 임시 블랙리스트행
                    }
                } else {
                    failedPlaceIdsThisSession.add(place.getId()); // AI가 응답을 안 해준 것도 임시 블랙리스트행
                }
            }
            System.out.println("[벌크 인리치먼트 성공] " + validPlacesForBulk.size() + "건 중 " + successCount + "건 테마/속성 적재 완료.");

            try {
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