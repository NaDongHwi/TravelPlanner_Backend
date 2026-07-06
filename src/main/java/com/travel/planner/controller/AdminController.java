package com.travel.planner.controller;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.service.AiService;
import com.travel.planner.service.GoogleMapsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "🔒 관리자 전용 데이터 정제 API", description = "오프라인 AI 데이터 파이프라인 인리치먼트 엔진 (담당: 나동휘)")
public class AdminController {

    private final PlaceRepository placeRepository;
    private final GoogleMapsService googleMapsService;
    private final AiService aiService;

    // 글로벌 스레드 제어용 플래그
    private volatile boolean isEnriching = false;

    @PostMapping("/enrich-themes")
    @Operation(summary = "오프라인 AI 테마 자동 분류 및 인리치먼트 구동 (백그라운드 자동화)",
            description = "DB 내부 명소 중 테마 정보가 비어있는 장소들을 30건씩 백그라운드에서 가져와 리뷰 분석 후 테마를 적재합니다.")
    public ResponseEntity<String> enrichMissingThemes() {
        if (isEnriching) {
            return ResponseEntity.badRequest().body("이미 테마 인리치먼트 작업이 실행 중입니다.");
        }

        isEnriching = true;

        new Thread(() -> {
            System.out.println("[어드민 엔진] 오프라인 AI 데이터 인리치먼트 백그라운드 파이프라인 가동...");

            // 이번 실행에서 형식이 깨져서 실패한 장소들의 ID를 기억하는 블랙리스트 (메모리에만 존재)
            java.util.Set<Long> skippedPlaceIds = new java.util.HashSet<>();

            while (isEnriching) {
                // 1. 테마나 실내/외 속성이 비어있는 데이터 30건씩 조회
                List<Place> allPlaces = placeRepository.findAll();
                List<Place> targetPlaces = allPlaces.stream()
                        .filter(p -> p.getTheme() == null || p.getTheme().trim().isEmpty()
                                || p.getPlaceType() == null || p.getPlaceType().trim().isEmpty())
                        .filter(p -> !skippedPlaceIds.contains(p.getId())) // 블랙리스트에 있는(실패했던) 장소는 제외하고 가져옴
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

                    // [방어 1] 429 에러 등으로 AI가 뻗었을 때 (null 반환)
                    if (extractedData == null || extractedData.trim().isEmpty()) {
                        System.out.println("[경고] AI 응답 없음 (429 제한 등). [" + place.getName() + "]에서 일시 정지합니다.");
                        hit429 = true;
                        break;
                    }

                    String[] parts = extractedData.split("\\|");

                    // [방어 2] 완벽한 양식일 때만 DB에 영구 저장
                    if (parts.length >= 2) {
                        place.setTheme(parts[0].trim());
                        place.setPlaceType(parts[1].trim());
                        placeRepository.save(place);
                        successCount++;
                        System.out.println("[인리치먼트 성공] " + place.getName() + " -> 테마:[" + place.getTheme() + "], 속성:[" + place.getPlaceType() + "]");
                    } else {
                        // 쓰레기 값을 DB에 넣는 대신, 메모리 블랙리스트에만 ID를 추가하고 넘어감
                        System.out.println("[형식 오류 스킵] AI 양식 위반: " + extractedData + " (DB 보존, 다음 턴에서 제외)");
                        skippedPlaceIds.add(place.getId());
                    }

                    try { Thread.sleep(3000); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }

                if (hit429) {
                    System.out.println("구글 API 한도 초기화를 위해 1분(60s) 동안 대기합니다...");
                    try {
                        Thread.sleep(60000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    System.out.println("사이클 완료. 한도 누적 방지를 위해 10초간 안전 휴식을 취합니다...");
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            System.out.println("[테마 인리치먼트 종료] 백그라운드 스레드가 안전하게 정지되었습니다.");
        }).start();

        return ResponseEntity.ok("자동 테마 인리치먼트 백그라운드 작업이 시작되었습니다. 429 에러 발생 시 1분간 자동 휴식 후 재시도합니다.");
    }

    @PostMapping("/enrich-themes/stop")
    @Operation(summary = "실행 중인 테마 자동 분류 작업 강제 중지")
    public ResponseEntity<String> stopEnriching() {
        if (!isEnriching) {
            return ResponseEntity.ok("현재 실행 중인 테마 정제 작업이 없습니다.");
        }

        isEnriching = false; // 플래그를 false로 부러뜨려 루프 탈출
        return ResponseEntity.ok("테마 정제 작업 중지 명령을 전송했습니다. 대기 시간이 끝나면 안전하게 중지됩니다.");
    }

    @PostMapping("/collect-places")
    @Operation(summary = "구글 API 기반 특정 도시 장소 자동 대량 수집 (자체 지명 검증 엔진 탑재)",
            description = "도시를 자유로운 텍스트로 입력받되, 내부적으로 구글 Geocoding을 통해 일본 내 정식 지명으로 가공하여 중복 및 동음이의어를 차단합니다.")
    public String collectNewPlaces(
            @RequestParam String city,
            @RequestParam(required = false) String keyword
    ) {
        String formalizedCity;

        // [에러 방어] 지명 검증 중 에러가 나면 쓰레기 데이터 검색을 즉시 차단합니다
        try {
            formalizedCity = googleMapsService.getFormalizedJapanCity(city);
            System.out.println("[어드민 대량 적재 엔진] 입력 지명: [" + city + "] -> 정제 지명: [" + formalizedCity + "]");
        } catch (RuntimeException e) {
            System.out.println("[차단됨] 쓰레기 데이터 유입을 막기 위해 수집을 취소합니다. 원인: " + e.getMessage());
            return "오류 발생: 정확한 일본 지명을 찾을 수 없어 데이터 수집을 취소합니다.";
        }

        String[] keywordSuite;
        if (keyword == null || keyword.trim().isEmpty()) {
            keywordSuite = new String[]{
                    "필수 명소", "유명 관광지", "랜드마크", "역사 유적지", "테마파크",
                    "핫플레이스 맛집", "유명 카페", "대형 쇼핑몰"
            };
        } else {
            keywordSuite = new String[]{keyword};
        }

        int totalInserted = 0;
        int totalSkipped = 0;

        for (String kw : keywordSuite) {
            // 구글 Geocoding으로 검증된 안전한 정식 지명("일본 나라현")을 기반으로 장소들을 긁어옵니다.
            List<Place> googlePlaces = googleMapsService.searchNewPlacesFromGoogle(formalizedCity, kw);

            for (Place googlePlace : googlePlaces) {
                // 구글 검색은 "일본 나라현"으로 안전하게 하되, 우리 DB 카테고리 기준에 맞게 원래 입력값인 "나라"로 명찰을 붙여 저장합니다.
                googlePlace.setCity(city);

                if (placeRepository.existsByPlaceId(googlePlace.getPlaceId())) {
                    totalSkipped++;
                    continue;
                }

                placeRepository.save(googlePlace);
                totalInserted++;
            }
        }

        return String.format("[%s] 대량 자동 수집 완료! -> 신규 명소 등록: %d건 / 기존 중복 패스: %d건",
                city, totalInserted, totalSkipped);
    }

    // 글로벌 스레드 제어용 플래그
    private volatile boolean isCleansing = false;

    @PostMapping("/cleanse-categories")
    @Operation(summary = "기존 데이터 AI 카테고리 완전 자동 분류 (30건씩 백그라운드 처리)")
    public ResponseEntity<String> cleanseCategories() {
        if (isCleansing) {
            return ResponseEntity.badRequest().body("이미 자동 정제 작업이 실행 중입니다.");
        }

        isCleansing = true;

        // 스레드 분리 실행
        new Thread(() -> {
            System.out.println("[자동 정제 시작] AI 카테고리 자동 분류를 시작합니다...");

            while (isCleansing) {
                // 1. 카테고리가 비어있는(null) 데이터 30개를 조회
                List<Place> allPlaces = placeRepository.findAll();
                List<Place> targetPlaces = allPlaces.stream()
                        .filter(p -> p.getCategory() == null)
                        .limit(30)
                        .collect(Collectors.toList());

                // 남은 데이터가 없으면 자동 종료
                if (targetPlaces.isEmpty()) {
                    System.out.println("[자동 정제 완료] 모든 데이터의 카테고리 분류가 100% 완료되었습니다!");
                    isCleansing = false;
                    break;
                }

                System.out.println("남은 데이터 정제 중... (현재 30건 처리 시도)");

                // 2. AI에게 분류 요청
                Map<String, String> categorizedMap = aiService.cleansePlaceCategories(targetPlaces);

                // 3. AI 요청 제한(429) 감지 시 대책 (1분 대기)
                if (categorizedMap == null || categorizedMap.isEmpty()) {
                    System.out.println("[경고] AI API 요청 제한(429) 감지!");
                    System.out.println("구글 API 한도 초기화를 위해 1분(60s) 동안 대기합니다...");

                    try {
                        Thread.sleep(60000); // 1분(60초) 대기
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue; // 1분 동안 쉬고 락이 풀린 상태에서 방금 실패한 30건 재시도
                }

                // 4. 성공 시 DB 저장
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

                // 5. 성공 후 예방적 휴식 시간 30초
                try {
                    System.out.println("API 한도 누적을 방지하기 위해 30초간 안전 휴식을 취합니다...");
                    Thread.sleep(30000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("[자동 정제 종료] 백그라운드 정제 스레드가 안전하게 정지되었습니다.");
        }).start();

        return ResponseEntity.ok("자동 정제 백그라운드 작업이 시작되었습니다. 429 에러 발생 시 1분간 자동 휴식 루틴이 가동됩니다.");
    }

    @PostMapping("/cleanse-categories/stop")
    @Operation(summary = "실행 중인 AI 카테고리 자동 분류 작업 강제 중지")
    public ResponseEntity<String> stopCleansing() {
        if (!isCleansing) {
            return ResponseEntity.ok("현재 실행 중인 자동 정제 작업이 없습니다.");
        }

        isCleansing = false; // 플래그를 false로 부러뜨려 루프 탈출
        return ResponseEntity.ok("정제 작업 중지 명령을 전송했습니다. 현재 사이클이 마무리되거나 대기 시간이 끝나면 안전하게 중지됩니다.");
    }
}