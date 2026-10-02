package com.travel.planner.service;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.PlanRequest;
import com.travel.planner.dto.RerouteRequest;
import com.travel.planner.dto.RerouteResponse;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.repository.ItineraryRepository;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlanModifierService {

    private final PlanRepository planRepository;
    private final PlaceRepository placeRepository;
    private final AiService aiService;
    private final PlanService planService;
    private final ItineraryRepository itineraryRepository; // ItineraryRepository 필요 시 생성 요망

    @Transactional
    public RerouteResponse modifyPlanRoute(Long planId, RerouteRequest request) {
        RerouteResponse response = new RerouteResponse();
        List<String> droppedPlaces = new ArrayList<>();

        // 1. 기존 여행 계획 및 해당 일차의 타임라인 불러오기
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("해당 일정을 찾을 수 없습니다."));

        List<Itinerary> targetDayItineraries = plan.getItineraries().stream()
                .filter(iti -> iti.getDayNumber() == request.getDayNumber())
                .sorted(java.util.Comparator.comparing(Itinerary::getSequence))
                .collect(Collectors.toList());

        List<Place> draftRoute = targetDayItineraries.stream()
                .map(Itinerary::getPlace)
                .collect(Collectors.toList());

        // 2. 신규 장소 식별 및 AI 실시간 체류시간 계산 (DB에 없는 경우)
        Place newPlace = placeRepository.findByPlaceId(request.getPlaceId()).orElseGet(() -> {
            Place p = new Place();
            p.setPlaceId(request.getPlaceId());
            p.setName(request.getPlaceName());
            p.setLatitude(request.getLatitude());
            p.setLongitude(request.getLongitude());
            p.setCity(request.getCity());
            p.setOpeningHours("영업시간 정보 없음");

            // AI 단건 호출로 낯선 장소의 체류 시간 실시간 획득
            aiService.inferPlaceAttributesRealTime(p);
            return placeRepository.save(p);
        });

        // 3. 사용자가 지정한 순서에 새 장소 끼워 넣기
        int insertIndex = Math.max(0, Math.min(request.getInsertSequence() - 1, draftRoute.size()));
        draftRoute.add(insertIndex, newPlace);

        // 4. 시뮬레이터를 통과시키기 위한 더미 설정 객체 생성
        PlanRequest dummyReq = buildDummyRequest(plan);
        int totalDays = (int) java.time.temporal.ChronoUnit.DAYS.between(plan.getStartDate(), plan.getEndDate()) + 1;

        // 5. 도미노 붕괴 검증 시뮬레이션 루프
        boolean isSuccess = false;
        while (!isSuccess) {
            PlanService.SimulationResult simResult = planService.runScheduleSimulation(
                    draftRoute, dummyReq, request.getDayNumber(), totalDays, false
            );

            if (simResult.isSuccess()) {
                isSuccess = true;

                // 성공 시 DB 타임라인 덮어쓰기 로직 (간략화)
                // 실제 서비스에서는 기존 Itinerary 삭제 후 새로 save 하는 로직이 들어갑니다.
                List<AiRouteResponse.TimelineItem> updatedTimeline = new ArrayList<>();
                for (PlanService.SimulatedItinerary simIti : simResult.getValidRoute()) {
                    AiRouteResponse.TimelineItem item = new AiRouteResponse.TimelineItem();
                    item.setDay(request.getDayNumber());
                    item.setTime(simIti.getTime());
                    item.setPlaceName(simIti.getPlace().getName());
                    updatedTimeline.add(item);
                }

                response.setSuccess(true);
                response.setRequireConfirmation(false);
                response.setDroppedPlaces(droppedPlaces);
                response.setUpdatedTimeline(updatedTimeline);
                response.setMessage("일정이 성공적으로 재계산되었습니다.");

            } else {
                // 도미노 붕괴 발생! (시간 오버)
                if (!request.isForceDrop()) {
                    // 강제 삭제 권한이 없다면 여기서 스톱하고 프론트에 경고창을 띄움
                    response.setSuccess(false);
                    response.setRequireConfirmation(true);
                    response.setMessage("[" + simResult.getProblemPlace().getName() + "] 장소에 도착 시 영업시간이 종료되거나 일과 시간이 초과됩니다. 해당 일정을 포기하고 추가하시겠습니까?");
                    return response;
                } else {
                    // 강제 삭제(사용자 동의) 상태라면, 문제가 된 장소를 리스트에서 빼버리고 루프 재실행
                    draftRoute.remove(simResult.getProblemPlace());
                    droppedPlaces.add(simResult.getProblemPlace().getName());
                }
            }
        }
        return response;
    }

    private PlanRequest buildDummyRequest(Plan plan) {
        PlanRequest req = new PlanRequest();
        req.setStartDate(plan.getStartDate());
        req.setEndDate(plan.getEndDate());
        req.setInTime(plan.getInTime());
        req.setOutTime(plan.getOutTime());
        req.setPreferredStartTime(LocalTime.of(9, 0));
        req.setPreferredEndTime(LocalTime.of(22, 0));
        req.setCompanion("친구");
        return req;
    }
}