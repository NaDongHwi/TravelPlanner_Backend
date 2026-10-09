package com.travel.planner.service;

import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.Accommodation;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.entity.Traffic;
import com.travel.planner.entity.User;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.repository.PlanRepository;
import com.travel.planner.repository.TrafficRepository;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.util.FixedScheduleCodec;
import com.travel.planner.util.ThemeVocabulary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 일정 저장 전담.
 * 외부 API 호출(구글·날씨)은 트랜잭션 밖에서 끝내고, DB 쓰기만 여기서 한 트랜잭션으로 묶는다.
 */
@Service
@RequiredArgsConstructor
public class PlanPersistenceService {

    private static final String DUMMY_FREE = "DUMMY_FREE_TIME";
    private static final String DUMMY_MEAL = "DUMMY_MEAL";
    private static final String DUMMY_FIXED = "DUMMY_FIXED_SCHEDULE";

    private final PlanRepository planRepository;
    private final PlaceRepository placeRepository;
    private final TrafficRepository trafficRepository;

    /** 구글에서 새로 받은 장소를 DB 에 넣고(이미 있으면 기존 것을) 돌려준다. */
    @Transactional
    public Place upsertPlace(Place fetched) {
        return placeRepository.findByPlaceId(fetched.getPlaceId())
                .orElseGet(() -> placeRepository.save(fetched));
    }

    @Transactional
    public Plan saveNewPlan(User user, PlanRequest request, List<PlanService.DayPlan> days,
                            List<Accommodation> accommodations, String reason) {
        Plan plan = new Plan();
        plan.setUser(user);
        plan.setTitle(String.join(", ", request.getCities()) + " 여행");
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());
        plan.setInCity(request.getInCity());
        plan.setOutCity(request.getOutCity());
        plan.setInTime(request.getInTime() != null ? request.getInTime() : "미정");
        plan.setOutTime(request.getOutTime() != null ? request.getOutTime() : "미정");
        if (request.getThemes() != null) plan.setTheme(String.join(", ", request.getThemes()));
        plan.setAiReason(reason);

        // reroute 에서 원래 조건을 복원할 수 있도록 요청 값을 함께 저장한다.
        plan.setCities(String.join(", ", request.getCities()));
        plan.setCompanion(request.getCompanion());
        plan.setTransportation(request.getTransportation());
        plan.setPreferredStartTime(request.getPreferredStartTime());
        plan.setPreferredEndTime(request.getPreferredEndTime());
        if (request.getExcludedThemes() != null) plan.setExcludedThemes(String.join(", ", request.getExcludedThemes()));
        plan.setFixedSchedulesJson(FixedScheduleCodec.toJson(request.getFixedSchedules()));
        plan.setLanguage(request.getLanguage());
        plan.setMealCount(request.getMealCount());
        plan.setExcludeCafe(request.getExcludeCafe());

        for (Accommodation accommodation : accommodations) {
            plan.addAccommodation(accommodation);
        }

        String transport = request.getTransportation() != null ? request.getTransportation() : "대중교통";
        List<Traffic> traffics = new ArrayList<>();
        for (PlanService.DayPlan day : days) {
            traffics.addAll(appendDay(plan, day.getDayNumber(), day.getItems(), transport));
        }

        Plan saved = planRepository.save(plan);   // itineraries·accommodations 는 cascade 로 함께 저장
        trafficRepository.saveAll(traffics);
        return saved;
    }

    /** 특정 일차의 일정을 새 결과로 교체한다 (reroute 결과 저장). */
    @Transactional
    public void replaceDay(Plan plan, int dayNumber, List<SimulatedItinerary> items) {
        List<Itinerary> old = plan.getItineraries().stream()
                .filter(i -> i.getDayNumber() != null && i.getDayNumber() == dayNumber)
                .collect(Collectors.toList());
        if (!old.isEmpty()) {
            trafficRepository.deleteByItineraryIn(old);   // Traffic 이 Itinerary 를 참조하므로 먼저 지운다
            trafficRepository.flush();
            plan.getItineraries().removeAll(old);          // orphanRemoval 로 삭제
            planRepository.saveAndFlush(plan);
        }

        String transport = plan.getTransportation() != null ? plan.getTransportation() : "대중교통";
        List<Traffic> traffics = appendDay(plan, dayNumber, items, transport);
        planRepository.saveAndFlush(plan);
        trafficRepository.saveAll(traffics);
    }

    /** 회원 탈퇴 등으로 계획을 지울 때: Traffic → Plan(→ Itinerary/Accommodation) 순서로 삭제 */
    @Transactional
    public void deletePlansOfUser(String email) {
        List<Plan> plans = planRepository.findAllByUserEmail(email);
        for (Plan plan : plans) {
            if (!plan.getItineraries().isEmpty()) {
                trafficRepository.deleteByItineraryIn(new ArrayList<>(plan.getItineraries()));
            }
        }
        trafficRepository.flush();
        planRepository.deleteAll(plans);
        planRepository.flush();
    }

    private List<Traffic> appendDay(Plan plan, int dayNumber, List<SimulatedItinerary> items, String transport) {
        List<Traffic> traffics = new ArrayList<>();
        int sequence = 1;
        for (SimulatedItinerary sim : items) {
            Itinerary itinerary = new Itinerary();
            itinerary.setDayNumber(dayNumber);
            itinerary.setSequence(sequence++);   // 그 날 안에서의 순서 (1, 2, 3 …)
            itinerary.setTime(sim.getTime());
            itinerary.setEndTime(sim.getEndTime());
            itinerary.setAiComment(TimelineAssembler.describe(sim));

            if (sim.getPlace() != null) {
                // 엔진이 돌려준 Place 객체를 그대로 연결한다.
                // (이전에는 이름으로 다시 찾아서 "스타벅스" 같은 체인이 다른 지점으로 저장됐다)
                itinerary.setPlace(sim.getPlace());
            } else {
                itinerary.setPlace(pseudoPlace(sim));
                itinerary.setCustomTitle(sim.getTitle());
            }
            plan.addItinerary(itinerary);

            Traffic traffic = new Traffic();
            traffic.setItinerary(itinerary);
            traffic.setTransportType(transport);
            traffic.setEstimatedCost(0);
            traffic.setDurationMinutes(sim.getTravelMinutes());   // 엔진이 계산한 이동 시간을 그대로 저장
            traffics.add(traffic);
        }
        return traffics;
    }

    /**
     * 자유시간·식사·고정 일정은 실제 장소가 없지만 Itinerary.place 가 NOT NULL 이라 공용 더미 Place 에 연결한다.
     * 더미의 좌표(0,0)는 어디에서도 거리 계산에 쓰지 않는다. 화면 좌표는 타임라인 항목 쪽에 직전 위치로 들어간다.
     */
    private Place pseudoPlace(SimulatedItinerary sim) {
        String id;
        String name;
        String category = sim.getCategory();
        switch (sim.getType()) {
            case MEAL: id = DUMMY_MEAL; name = "[자유 식사]"; break;
            case FIXED: id = DUMMY_FIXED; name = "[고정 일정]"; break;
            default: id = DUMMY_FREE; name = "[자유 시간 및 로컬 탐방]"; break;
        }
        return placeRepository.findByPlaceId(id).orElseGet(() -> {
            Place dummy = new Place();
            dummy.setPlaceId(id);
            dummy.setName(name);
            dummy.setCategory(category);
            dummy.setLatitude(0.0);
            dummy.setLongitude(0.0);
            return placeRepository.save(dummy);
        });
    }

    /** 저장된 Plan 에서 원래 요청 조건을 복원한다 (reroute 용). */
    public PlanRequest restoreRequest(Plan plan) {
        PlanRequest req = new PlanRequest();
        req.setStartDate(plan.getStartDate());
        req.setEndDate(plan.getEndDate());
        req.setInCity(plan.getInCity());
        req.setOutCity(plan.getOutCity());
        req.setInTime(plan.getInTime());
        req.setOutTime(plan.getOutTime());
        req.setCities(splitList(plan.getCities()));
        req.setThemes(ThemeVocabulary.normalizeList(plan.getTheme()));
        req.setExcludedThemes(splitList(plan.getExcludedThemes()));
        req.setCompanion(plan.getCompanion());
        req.setTransportation(plan.getTransportation());
        req.setPreferredStartTime(plan.getPreferredStartTime());
        req.setPreferredEndTime(plan.getPreferredEndTime());
        req.setFixedSchedules(FixedScheduleCodec.fromJson(plan.getFixedSchedulesJson()));
        req.setLanguage(plan.getLanguage());
        req.setMealCount(plan.getMealCount());
        req.setExcludeCafe(plan.getExcludeCafe());
        return req;
    }

    private static List<String> splitList(String joined) {
        if (joined == null || joined.isBlank()) return new ArrayList<>();
        return Arrays.stream(joined.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }
}
