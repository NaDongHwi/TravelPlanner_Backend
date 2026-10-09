package com.travel.planner.scheduler;

import com.travel.planner.entity.Place;
import com.travel.planner.entity.TransportPass;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.repository.TransportPassRepository;
import com.travel.planner.service.GoogleMapsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
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
     * 하룻밤에 구글에 다시 조회할 장소 수 상한.
     * 이전에는 lastUpdated 가 비어 있는 모든 장소(= 사실상 DB 전체)를 첫 실행에 한꺼번에 재조회했다.
     * 오래된 것부터 이만큼씩만 돌리면 비용이 예측 가능하고, 30일 안에 (상한 × 30)건을 순환한다.
     */
    @Value("${app.refresh.max-places-per-night:150}")
    private int maxPlacesPerNight;

    /**
     * [30일 주기 최신화 스케줄러]
     * 매일 새벽 3시 10분, lastUpdated 가 30일 지난 장소를 오래된 순으로 일부만 갱신한다.
     */
    @Scheduled(cron = "0 10 3 * * *")
    public void autoRefreshOldTravelData() {
        System.out.println("[배치 데몬] 30일 경과 데이터 정기 리프레시 스케줄러 기동...");
        LocalDateTime expirationThreshold = LocalDateTime.now().minusDays(30);

        // 1. 장소 데이터 갱신 (Place ID 기준. 좌표는 덮어쓰지 않는다)
        List<Place> targets = placeRepository.findRefreshTargets(expirationThreshold, PageRequest.of(0, Math.max(1, maxPlacesPerNight)));
        int refreshed = 0;
        for (Place place : targets) {
            if (googleMapsService.refreshPlace(place, "ko")) refreshed++;
            // 조회에 실패했거나 구글에서 사라진 장소도 시각은 찍는다.
            // 그러지 않으면 같은 장소가 매일 밤 다시 대상이 되어 과금만 쌓인다.
            place.setLastUpdated(LocalDateTime.now());
            placeRepository.save(place);
        }
        System.out.println("[배치 데몬] 장소 " + targets.size() + "건 중 " + refreshed + "건 최신화.");

        // 2. 교통 패스 데이터 갱신 파이프라인 (패스 가격 인상이나 정책 변경 주기 추적용)
        List<TransportPass> allPasses = transportPassRepository.findAll();
        for (TransportPass pass : allPasses) {
            if (pass.getLastUpdated() == null || pass.getLastUpdated().isBefore(expirationThreshold)) {
                // 나중에 외부 패스 인상률 변동 API 등을 붙이거나 현 상태를 최신화 컨디션으로 타임스탬프 도장 처리
                pass.setLastUpdated(LocalDateTime.now());
                transportPassRepository.save(pass);
            }
        }
        System.out.println("[배치 데몬] 30일 주기 정기 정제 완료.");
    }
}
