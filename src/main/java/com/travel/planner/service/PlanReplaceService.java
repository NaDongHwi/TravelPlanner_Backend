package com.travel.planner.service;

import com.travel.planner.dto.PlanRequest;
import com.travel.planner.dto.ReplaceRequest;
import com.travel.planner.dto.ReplaceResponse;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.repository.PlanRepository;
import com.travel.planner.service.PlanService.DayItem;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.util.ThemeVocabulary;
import com.travel.planner.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 만들어진 일정에서 마음에 들지 않는 줄만 골라 다른 장소로 바꾼다.
 *
 * 나머지 줄은 장소도 시각도 그대로 둔다. 고른 줄의 앞뒤 일정 사이 시간에 맞는 곳만 넣으므로(엔진 PlanService.replaceInDay)
 * 뒤 일정이 밀리거나 빠지지 않는다. 바꾼 장소는 계획에 기록해 두어, 다시 바꿔 달라고 해도 같은 곳이 또 나오지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PlanReplaceService {

    private static final int MAX_TARGETS = 20;

    private final PlanRepository planRepository;
    private final PlaceRepository placeRepository;
    private final PlanService planService;
    private final PlanPersistenceService planPersistenceService;
    private final TimelineAssembler timelineAssembler;
    private final PlaceDescriptionService placeDescriptionService;

    @Transactional
    public ReplaceResponse replace(String email, Long planId, ReplaceRequest request) {
        Plan plan = planRepository.findDetailById(planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 일정을 찾을 수 없습니다."));
        if (plan.getUser() == null || !plan.getUser().getEmail().equals(email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 일정만 수정할 수 있습니다.");
        }
        if (request == null) throw new IllegalArgumentException("요청 본문이 비어 있습니다.");

        String theme = null;
        if (request.getTheme() != null && !request.getTheme().isBlank()) {
            theme = ThemeVocabulary.normalize(request.getTheme());
            if (theme == null) {
                throw new IllegalArgumentException("테마는 [" + String.join(", ", ThemeVocabulary.THEMES) + "] 중 하나여야 합니다.");
            }
        }

        int totalDays = (int) java.time.temporal.ChronoUnit.DAYS.between(plan.getStartDate(), plan.getEndDate()) + 1;

        // 일차별 일정 (그 날 화면 순서대로)
        Map<Integer, List<Itinerary>> byDay = new TreeMap<>();
        for (Itinerary itinerary : plan.getItineraries()) {
            if (itinerary.getDayNumber() == null) continue;
            byDay.computeIfAbsent(itinerary.getDayNumber(), d -> new ArrayList<>()).add(itinerary);
        }
        for (List<Itinerary> list : byDay.values()) list.sort(Comparator.comparing(Itinerary::getSequence));

        // 바꿀 줄: 일차 → 그 날 순번(1부터)
        Map<Integer, Set<Integer>> targets = resolveTargets(request, byDay, totalDays);
        if (targets.isEmpty()) throw new IllegalArgumentException("바꿀 일정을 1개 이상 골라 주세요. (targets 또는 placeIds)");
        if (targets.values().stream().mapToInt(Set::size).sum() > MAX_TARGETS) {
            throw new IllegalArgumentException("한 번에 바꿀 수 있는 일정은 " + MAX_TARGETS + "개까지입니다.");
        }

        PlanRequest restored = planPersistenceService.restoreRequest(plan);
        List<Place> candidates = placeRepository.findByCityIn(restored.getCities());

        // 여행 전체에서 이미 쓰인 장소(중복·같은 체인 방지)와, 이전에 바꿔 달라고 했던 장소
        List<Place> tripPlaces = plan.getItineraries().stream().map(Itinerary::getPlace)
                .filter(p -> p != null && !PlanService.isPseudoCategory(p.getCategory())).collect(Collectors.toList());
        Set<String> rejected = new LinkedHashSet<>(splitIds(plan.getRejectedPlaceIds()));

        ReplaceResponse response = new ReplaceResponse();
        Map<Integer, List<SimulatedItinerary>> newDays = new TreeMap<>();
        Map<Integer, PlanService.DayContext> contexts = new TreeMap<>();
        List<Place> newPlaces = new ArrayList<>();

        for (Map.Entry<Integer, Set<Integer>> entry : targets.entrySet()) {
            int dayNumber = entry.getKey();
            List<Itinerary> itineraries = byDay.get(dayNumber);
            List<DayItem> day = toDayItems(itineraries);
            for (int sequence : entry.getValue()) day.get(sequence - 1).replace = true;

            Place startAnchor = day.get(0).type == SimulatedItinerary.Type.START ? day.get(0).place : null;
            Place endAnchor = day.get(day.size() - 1).type == SimulatedItinerary.Type.END ? day.get(day.size() - 1).place : null;
            PlanService.DayContext ctx = planService.buildDayContext(restored, dayNumber, totalDays, startAnchor, endAnchor);

            PlanService.ReplaceResult result = planService.replaceInDay(restored, candidates, ctx, day, tripPlaces, rejected, theme);

            for (PlanService.Replacement r : result.replacements) {
                ReplaceResponse.Change change = new ReplaceResponse.Change();
                change.setDayNumber(dayNumber);
                change.setSequence(r.sequence);
                change.setChanged(r.changed);
                change.setOldPlaceName(r.oldName);
                if (r.oldPlace != null) change.setOldPlaceId(r.oldPlace.getPlaceId());
                if (r.newPlace != null) {
                    change.setNewPlaceId(r.newPlace.getPlaceId());
                    change.setNewPlaceName(r.newPlace.getName());
                    newPlaces.add(r.newPlace);
                    tripPlaces.add(r.newPlace);                                    // 다른 날에서 같은 곳을 또 고르지 않도록
                    if (r.oldPlace != null) rejected.add(r.oldPlace.getPlaceId()); // 다시 추천하지 않는다
                }
                change.setMessage(r.message);
                response.getChanges().add(change);
            }
            if (result.replacements.stream().anyMatch(r -> r.changed)) {
                newDays.put(dayNumber, result.items);
                contexts.put(dayNumber, ctx);
            }
        }

        long changed = response.getChanges().stream().filter(ReplaceResponse.Change::isChanged).count();
        long requested = response.getChanges().size();
        response.setSuccess(changed > 0);

        // 새로 들어간 장소에 AI 소개가 없으면 지금 채운다
        placeDescriptionService.fillMissing(newPlaces);

        boolean save = changed > 0 && !request.isPreview();
        for (Map.Entry<Integer, List<SimulatedItinerary>> entry : newDays.entrySet()) {
            int dayNumber = entry.getKey();
            PlanService.DayContext ctx = contexts.get(dayNumber);
            if (save) planPersistenceService.replaceDay(plan, dayNumber, entry.getValue());
            response.getUpdatedTimeline().addAll(timelineAssembler.toTimeline(dayNumber, ctx.getDate(), entry.getValue(), restored.getLanguage()));
            response.getUpdatedDaySummaries().add(PlanSummarizer.summarizeDay(dayNumber, ctx.getDate(), ctx.getCity(), entry.getValue(), null, null));
        }
        if (save) {
            plan.setRejectedPlaceIds(String.join(",", rejected));
            planRepository.save(plan);
        }
        response.setSaved(save);

        if (changed == 0) {
            response.setMessage("바꿀 수 있는 곳을 찾지 못해 일정을 그대로 두었습니다.");
        } else if (changed < requested) {
            response.setMessage(requested + "곳 중 " + changed + "곳을 바꿨습니다. 나머지는 앞뒤 일정에 맞는 곳이 없어 그대로 두었습니다."
                    + (request.isPreview() ? " (미리보기: 저장하지 않았습니다)" : ""));
        } else {
            response.setMessage(changed + "곳을 바꿨습니다. 다른 일정의 장소와 시간은 그대로입니다."
                    + (request.isPreview() ? " (미리보기: 저장하지 않았습니다)" : ""));
        }
        return response;
    }

    /** 요청의 targets(일차·순번)와 placeIds 를 "일차 → 순번 집합"으로 모은다. */
    private Map<Integer, Set<Integer>> resolveTargets(ReplaceRequest request, Map<Integer, List<Itinerary>> byDay, int totalDays) {
        Map<Integer, Set<Integer>> targets = new TreeMap<>();
        if (request.getTargets() != null) {
            for (ReplaceRequest.Target t : request.getTargets()) {
                if (t == null) continue;
                if (t.getDayNumber() < 1 || t.getDayNumber() > totalDays) {
                    throw new IllegalArgumentException("일차가 여행 기간(1~" + totalDays + "일차)을 벗어납니다: " + t.getDayNumber());
                }
                List<Itinerary> day = byDay.get(t.getDayNumber());
                if (day == null || t.getSequence() < 1 || t.getSequence() > day.size()) {
                    throw new IllegalArgumentException(t.getDayNumber() + "일차에 " + t.getSequence() + "번째 일정이 없습니다.");
                }
                targets.computeIfAbsent(t.getDayNumber(), d -> new TreeSet<>()).add(t.getSequence());
            }
        }
        if (request.getPlaceIds() != null) {
            for (String placeId : request.getPlaceIds()) {
                if (placeId == null || placeId.isBlank()) continue;
                boolean found = false;
                for (Map.Entry<Integer, List<Itinerary>> entry : byDay.entrySet()) {
                    List<Itinerary> day = entry.getValue();
                    for (int i = 0; i < day.size(); i++) {
                        Place p = day.get(i).getPlace();
                        if (p == null || !placeId.equals(p.getPlaceId())) continue;
                        // 숙소·공항은 매일 출발·도착에 나오므로 placeId 로는 고를 수 없다 (바꿀 수도 없다)
                        if (PlanService.isLodging(p) || PlanService.isAirport(p)) continue;
                        targets.computeIfAbsent(entry.getKey(), d -> new TreeSet<>()).add(i + 1);
                        found = true;
                    }
                }
                if (!found) throw new IllegalArgumentException("이 일정에 없는 장소이거나 바꿀 수 없는 항목입니다: " + placeId);
            }
        }
        return targets;
    }

    /** 저장된 하루 일정을 엔진 입력으로 바꾼다. */
    static List<DayItem> toDayItems(List<Itinerary> itineraries) {
        List<DayItem> day = new ArrayList<>();
        int last = 0;
        for (int i = 0; i < itineraries.size(); i++) {
            Itinerary itinerary = itineraries.get(i);
            Place place = itinerary.getPlace();
            String placeId = place == null || place.getPlaceId() == null ? "" : place.getPlaceId();
            boolean anchorLike = place != null && (PlanService.isLodging(place) || PlanService.isAirport(place));

            SimulatedItinerary.Type type;
            if (placeId.equals("DUMMY_MEAL")) type = SimulatedItinerary.Type.MEAL;
            else if (placeId.equals("DUMMY_FIXED_SCHEDULE")) type = SimulatedItinerary.Type.FIXED;
            else if (placeId.startsWith("DUMMY_") || place == null) type = SimulatedItinerary.Type.FREE;
            else if (anchorLike && i == 0) type = SimulatedItinerary.Type.START;
            else if (anchorLike && i == itineraries.size() - 1) type = SimulatedItinerary.Type.END;
            else type = SimulatedItinerary.Type.VISIT;

            boolean pseudo = type == SimulatedItinerary.Type.MEAL || type == SimulatedItinerary.Type.FIXED || type == SimulatedItinerary.Type.FREE;
            String title = itinerary.getCustomTitle() != null ? itinerary.getCustomTitle() : place != null ? place.getName() : "";
            // 저장된 시각은 "HH:mm" 이라 자정을 넘긴 일정은 00:30 처럼 적혀 있다. 앞 줄보다 이르면 다음 날로 본다.
            int start = TimeUtil.parseClock(itinerary.getTime());
            while (start < last) start += 1440;
            Integer end = null;
            if (itinerary.getEndTime() != null) {
                end = TimeUtil.parseClock(itinerary.getEndTime());
                while (end < start) end += 1440;
            }
            last = end != null ? end : start;
            day.add(new DayItem(type, pseudo ? null : place, title, start, end));
        }
        return day;
    }

    private static List<String> splitIds(String joined) {
        if (joined == null || joined.isBlank()) return new ArrayList<>();
        return Arrays.stream(joined.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }
}
