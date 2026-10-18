package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.util.Hiking;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 등산하는 곳(산 정상 등)에 어떻게 가는지 미리 알아 둔다.
 *  - 일정을 짜기 전: 정상 1.5km 안에 역·버스 정류장이 있는가 (없으면 대중교통 여행에서 점수를 조금 낮춘다)
 *  - 일정에 들어간 뒤: 그런 곳이면 정상 근처 주차장을 구글에서 한 번 찾아 DB 에 저장한다 (택시로 주차장까지 안내하는 데 쓴다)
 */
@Service
@RequiredArgsConstructor
public class HikeAccessService {

    /** 주차장을 찾는 반경(m) */
    static final double PARKING_RADIUS_M = 2000;
    /** 찾았는데 없을 때 저장하는 표식 */
    public static final String NO_PARKING = "-";

    private final RailRouteService railRouteService;
    private final GoogleMapsService googleMapsService;
    private final PlaceRepository placeRepository;

    /** 후보 중 등산하는 곳마다 "1.5km 안에 역·정류장이 있는가"를 적어 둔다 (일정 엔진이 읽는다) */
    public void markTransitAccess(Collection<Place> candidates) {
        if (candidates == null) return;
        for (Place p : candidates) {
            if (p == null || p.getLatitude() == null || p.getLongitude() == null || !Hiking.isHike(p)) continue;
            p.setHikeTransitGate(railRouteService.hasTransitNear(p.getLatitude(), p.getLongitude(), Hiking.TRANSIT_GATE_KM));
        }
    }

    /** 일정에 들어간 등산하는 곳 가운데, 대중교통 입구가 없고 주차장을 아직 찾아보지 않은 곳의 주차장을 찾아 저장한다 */
    public int fillParking(Collection<Place> places) {
        if (places == null) return 0;
        Map<String, Place> targets = new LinkedHashMap<>();
        for (Place p : places) {
            if (p == null || p.getPlaceId() == null || p.getLatitude() == null || p.getLongitude() == null) continue;
            if (!Hiking.isHike(p) || p.getTrailheadName() != null) continue;
            if (railRouteService.hasTransitNear(p.getLatitude(), p.getLongitude(), Hiking.TRANSIT_GATE_KM)) continue;
            targets.putIfAbsent(p.getPlaceId(), p);
        }
        int found = 0;
        for (Place p : targets.values()) {
            Object[] parking = googleMapsService.findNearestParking(p.getLatitude(), p.getLongitude(), PARKING_RADIUS_M);
            if (parking == null) continue;                         // 호출 실패: 다음에 다시 찾는다
            if (parking.length == 0) {
                p.setTrailheadName(NO_PARKING);
            } else {
                p.setTrailheadLat((Double) parking[0]);
                p.setTrailheadLng((Double) parking[1]);
                p.setTrailheadName(cut((String) parking[2]));
                found++;
            }
            try {
                if (p.getId() != null) placeRepository.updateTrailhead(p.getId(), p.getTrailheadLat(), p.getTrailheadLng(), p.getTrailheadName());
            } catch (Exception e) {
                System.out.println("[등산] 주차장 저장 실패(" + p.getName() + "): " + e.getMessage());
            }
        }
        if (!targets.isEmpty()) System.out.println("[등산] 대중교통 입구가 없는 등산하는 곳 " + targets.size() + "곳 중 " + found + "곳의 주차장을 찾았습니다.");
        return found;
    }

    private static String cut(String s) {
        if (s == null || s.isBlank()) return "주차장";
        return s.length() > 100 ? s.substring(0, 100) : s;
    }
}
