package com.travel.planner.service;

import com.travel.planner.dto.PlanRequest;
import com.travel.planner.dto.RerouteRequest;
import com.travel.planner.dto.RerouteResponse;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    private final PlanPersistenceService planPersistenceService;
    private final TimelineAssembler timelineAssembler;

    /**
     * 특정 일차에 장소 하나를 끼워 넣는다.
     *  - 사용자가 정한 순서 그대로 시간을 다시 계산해, 영업시간·일과 종료·고정 일정·출국 마감을 어기는 곳이 생기면
     *    forceDrop=false 일 때는 확인을 요청하고, true 일 때는 문제가 된 기존 장소를 빼고 다시 계산한다.
     *  - 성공하면 그 날 일정을 DB 에 저장한다. (이전에는 계산 결과를 돌려주기만 하고 저장하지 않았다)
     */
    @Transactional
    public RerouteResponse modifyPlanRoute(String email, Long planId, RerouteRequest request) {
        RerouteResponse response = new RerouteResponse();
        List<String> droppedPlaces = new ArrayList<>();

        // 1. 여행 계획 조회 + 본인 소유 확인
        Plan plan = planRepository.findDetailById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 일정을 찾을 수 없습니다."));
        if (plan.getUser() == null || !plan.getUser().getEmail().equals(email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 일정만 수정할 수 있습니다.");
        }

        int totalDays = (int) java.time.temporal.ChronoUnit.DAYS.between(plan.getStartDate(), plan.getEndDate()) + 1;
        int dayNumber = request.getDayNumber();
        if (dayNumber < 1 || dayNumber > totalDays) {
            throw new IllegalArgumentException("수정할 일차가 여행 기간(1~" + totalDays + "일차)을 벗어납니다.");
        }
        if (request.getPlaceId() == null || request.getPlaceId().isBlank()) {
            throw new IllegalArgumentException("추가할 장소의 placeId 가 필요합니다.");
        }

        List<Itinerary> dayItineraries = plan.getItineraries().stream()
                .filter(iti -> iti.getDayNumber() != null && iti.getDayNumber() == dayNumber)
                .sorted(java.util.Comparator.comparing(Itinerary::getSequence))
                .collect(Collectors.toList());

        // 2. 출발/도착 앵커(숙소·공항)와 실제 방문지를 분리한다.
        //    자유시간·식사 같은 더미 항목은 재계산 대상에서 빼고, 고정 일정은 저장된 요청 조건에서 다시 만든다.
        Place startAnchor = null;
        Place endAnchor = null;
        List<Place> draftRoute = new ArrayList<>();
        int insertIndex = 0;
        for (int i = 0; i < dayItineraries.size(); i++) {
            Itinerary iti = dayItineraries.get(i);
            Place p = iti.getPlace();
            if (p == null || PlanService.isPseudoCategory(p.getCategory())) continue;
            boolean anchorLike = PlanService.isLodging(p) || PlanService.isAirport(p);
            if (anchorLike && i == 0) {
                startAnchor = p;
            } else if (anchorLike && i == dayItineraries.size() - 1) {
                endAnchor = p;
            } else {
                // insertSequence 는 화면에 보이는 그 날의 순번(1부터, 앵커·더미 포함) 기준이므로 실제 방문지 목록의 위치로 바꾼다.
                // (저장된 sequence 값이 아니라 그 날 안에서의 순위를 쓴다: 예전 데이터는 sequence 가 여행 전체 통번호였다)
                if (i + 1 < request.getInsertSequence()) insertIndex = draftRoute.size() + 1;
                draftRoute.add(p);
            }
        }

        if (draftRoute.stream().anyMatch(p -> request.getPlaceId().equals(p.getPlaceId()))) {
            throw new IllegalArgumentException("이미 그 날 일정에 들어 있는 장소입니다.");
        }

        // 3. 신규 장소 식별 (DB에 없으면 AI 로 체류시간 추론 후 저장)
        Place newPlace = placeRepository.findByPlaceId(request.getPlaceId()).orElseGet(() -> {
            if (request.getPlaceName() == null || request.getPlaceName().isBlank()) {
                throw new IllegalArgumentException("새 장소의 이름(placeName)이 필요합니다.");
            }
            if (request.getLatitude() == 0.0 && request.getLongitude() == 0.0) {
                throw new IllegalArgumentException("새 장소의 좌표(latitude/longitude)가 필요합니다.");
            }
            Place p = new Place();
            p.setPlaceId(request.getPlaceId());
            p.setName(request.getPlaceName());
            p.setLatitude(request.getLatitude());
            p.setLongitude(request.getLongitude());
            p.setCity(request.getCity());

            // AI 단건 호출로 낯선 장소의 체류 시간 실시간 획득
            aiService.inferPlaceAttributesRealTime(p);
            return placeRepository.save(p);
        });

        insertIndex = Math.max(0, Math.min(insertIndex, draftRoute.size()));
        draftRoute.add(insertIndex, newPlace);

        // 4. 저장해 둔 원래 요청 조건(테마·동행·선호 시간·고정 일정·이동수단)을 복원
        PlanRequest restored = planPersistenceService.restoreRequest(plan);

        // 5. 도미노 붕괴 검증 루프
        while (true) {
            PlanService.DayContext ctx = planService.buildDayContext(restored, dayNumber, totalDays, startAnchor, endAnchor);
            PlanService.SimulationResult sim = planService.simulateFixedOrder(ctx, draftRoute, restored);

            if (sim.isSuccess()) {
                planPersistenceService.replaceDay(plan, dayNumber, sim.getValidRoute());

                response.setSuccess(true);
                response.setRequireConfirmation(false);
                response.setDroppedPlaces(droppedPlaces);
                response.setUpdatedTimeline(timelineAssembler.toTimeline(dayNumber, ctx.getDate(), sim.getValidRoute(), restored.getLanguage()));
                response.setUpdatedDaySummary(PlanSummarizer.summarizeDay(dayNumber, ctx.getDate(), ctx.getCity(), sim.getValidRoute(), null, null));
                response.setMessage(droppedPlaces.isEmpty()
                        ? "일정이 성공적으로 재계산되었습니다."
                        : "일정이 재계산되었습니다. 시간 부족으로 제외된 장소: " + String.join(", ", droppedPlaces));
                return response;
            }

            Place problem = sim.getProblemPlace();
            response.setDroppedPlaces(droppedPlaces);

            // 새로 넣으려는 장소 자체가 그 자리에 들어갈 수 없는 경우: 다른 장소를 빼도 해결되지 않는다.
            if (problem == null || problem.getPlaceId().equals(newPlace.getPlaceId())) {
                response.setSuccess(false);
                response.setRequireConfirmation(false);
                response.setMessage("이 순서에는 장소를 추가할 수 없습니다. " + sim.getReason());
                return response;
            }

            if (!request.isForceDrop()) {
                // 강제 삭제 동의가 없으면 여기서 멈추고 프론트에 확인 창을 띄운다. (아무것도 저장하지 않음)
                response.setSuccess(false);
                response.setRequireConfirmation(true);
                response.setMessage(sim.getReason() + " [" + problem.getName() + "] 일정을 포기하고 추가하시겠습니까?");
                return response;
            }

            // 사용자 동의 상태: 문제가 된 기존 장소를 빼고 다시 계산
            draftRoute.remove(problem);
            droppedPlaces.add(problem.getName());
        }
    }
}
