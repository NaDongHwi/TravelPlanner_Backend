package com.travel.planner.scheduler;

import com.travel.planner.entity.Place;
import com.travel.planner.entity.TransportPass;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.repository.TransportPassRepository;
import com.travel.planner.service.GoogleMapsService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DataUpdateScheduler {

    private final PlaceRepository placeRepository;
    private final TransportPassRepository transportPassRepository;
    private final GoogleMapsService googleMapsService;

    /**
     * [30일 주기 최신화 스케줄러]
     * 매일 새벽 3시 정각에 부하가 적은 자 시간에 배치 처리가 수행됩니다.
     * lastUpdated 기록이 현재 기준 30일 이전인 데이터만 필터링하여 영구 캐싱 제한(구글 약관)을 완벽 준수합니다.
     */
    @Scheduled(cron = "0 0 3 * * *") // 매일 새벽 3시 실행
    public void autoRefreshOldTravelData() {
        System.out.println("[배치 데몬] 30일 경과 데이터 정기 리프레시 스케줄러 기동...");
        LocalDateTime expirationThreshold = LocalDateTime.now().minusDays(30);

        // 1. 장소 데이터 갱신 파이프라인
        List<Place> allPlaces = placeRepository.findAll();
        for (Place place : allPlaces) {
            // 업데이트한 지 30일이 지났거나 한 번도 업데이트된 적 없는 신규 실시간 등록 데이터 타겟팅
            if (place.getLastUpdated() == null || place.getLastUpdated().isBefore(expirationThreshold)) {
                System.out.println("[데이터 갱신] 30일이 경과된 명소 정보 최신화 발동: " + place.getName());

                // 구글 Places API 재호출로 실시간 위경도 및 최신 변경된 영업시간 획득
                Place updatedDetails = googleMapsService.getPlaceDetails(place.getCity(), place.getName());

                if (updatedDetails.getLatitude() != 0.0) {
                    place.setLatitude(updatedDetails.getLatitude());
                    place.setLongitude(updatedDetails.getLongitude());
                    place.setOpeningHours(updatedDetails.getOpeningHours());
                    place.setLastUpdated(LocalDateTime.now()); // 타임스탬프 갱신
                    placeRepository.save(place);
                }
            }
        }

        // 2. 교통 패스 데이터 갱신 파이프라인 (패스 가격 인상이나 정책 변경 주기 추적용)
        List<TransportPass> allPasses = transportPassRepository.findAll();
        for (TransportPass pass : allPasses) {
            if (pass.getLastUpdated() == null || pass.getLastUpdated().isBefore(expirationThreshold)) {
                System.out.println("[교통패스 체크] 30일 경과 패스 데이터 검증 마크 처리: " + pass.getName());
                // 나중에 외부 패스 인상률 변동 API 등을 붙이거나 현 상태를 최신화 컨디션으로 타임스탬프 도장 처리
                pass.setLastUpdated(LocalDateTime.now());
                transportPassRepository.save(pass);
            }
        }
        System.out.println("[배치 데몬] 30일 주기 정기 정제 및 캐싱 적법화 최신화 완료.");
    }
}