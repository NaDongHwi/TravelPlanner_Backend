package com.travel.planner.service;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.Accommodation;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.entity.User;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.repository.UserRepository;
import com.travel.planner.util.AirportDirectory;
import com.travel.planner.util.DistanceUtil;
import com.travel.planner.util.ThemeVocabulary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 일정 생성 전체 흐름.
 *   입력 검증 → 후보 수집(부족하면 긴급 수집) → 공항·숙소 확정 → 방문일 예보 → 엔진 → 저장 → 응답
 * 컨트롤러에 있던 로직을 옮겨 왔다. 컨트롤러는 인증 정보만 꺼내 이 서비스를 호출한다.
 */
@Service
@RequiredArgsConstructor
public class PlanGenerationService {

    private static final int MAX_TRIP_DAYS = 30;

    private final PlanService planService;
    private final PlanValidationService planValidationService;
    private final PlanPersistenceService planPersistenceService;
    private final TimelineAssembler timelineAssembler;
    private final PlaceRepository placeRepository;
    private final UserRepository userRepository;
    private final GoogleMapsService googleMapsService;
    private final WeatherService weatherService;

    public AiRouteResponse createPlan(String email, PlanRequest request) {
        // ------------------------------------------------------------------
        // Step 1. 입력 검증 (잘못된 입력은 400 으로 돌려준다)
        // ------------------------------------------------------------------
        validate(request);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        int totalDays = planService.totalDays(request);
        List<String> cities = request.getCities();
        String mainCity = cities.get(0);
        List<String> warnings = new ArrayList<>();

        // ------------------------------------------------------------------
        // Step 2. 후보 장소 수집 (DB → 부족하면 구글 긴급 수집)
        // ------------------------------------------------------------------
        List<Place> allPlaces = new ArrayList<>(placeRepository.findByCityIn(cities));
        supplementCandidates(request, totalDays, allPlaces);

        if (allPlaces.stream().noneMatch(planService::isVisitCandidate)) {
            throw new PlanGenerationException("선택한 도시(" + String.join(", ", cities)
                    + ")에 방문 가능한 장소 데이터가 없습니다. 관리자 장소 수집을 먼저 실행해 주세요.");
        }
        Map<String, double[]> centers = planService.cityCenters(allPlaces, cities);

        // ------------------------------------------------------------------
        // Step 3. 입·출국 공항 (출국은 outCity 기준. 없으면 입국 공항과 같다고 본다)
        // ------------------------------------------------------------------
        Place arrivalAirport = resolveAirport(request.getInCity(), centers, mainCity, "입국", warnings);
        String outCity = isBlank(request.getOutCity()) ? request.getInCity() : request.getOutCity();
        Place departureAirport = resolveAirport(outCity, centers, cities.get(cities.size() - 1), "출국", warnings);

        // ------------------------------------------------------------------
        // Step 4. 숙소 (날짜별) + 일차별 도시 배정
        // ------------------------------------------------------------------
        Map<Integer, Place> lodgingByNight = new HashMap<>();
        List<Accommodation> accommodationEntities = new ArrayList<>();
        List<String> dayCities;

        boolean hasUserLodging = request.getAccommodations() != null && !request.getAccommodations().isEmpty();
        if (hasUserLodging) {
            resolveUserAccommodations(request, totalDays, centers, mainCity, lodgingByNight, accommodationEntities, warnings);
            dayCities = planService.assignDayCities(request, totalDays, centers, arrivalAirport, departureAirport, lodgingByNight);
        } else {
            // 숙소가 정해져 있지 않으면 도시별 볼거리 분량을 보고 일수를 나눈다 (볼거리가 적은 도시에 이틀을 주지 않는다)
            dayCities = planService.assignDayCities(request, totalDays, centers, arrivalAirport, departureAirport, lodgingByNight,
                    planService.sightMinutesByCity(request, allPlaces));
            if (request.isSuggestHotel() && totalDays > 1) {
                suggestHotels(request, totalDays, dayCities, centers, mainCity, lodgingByNight, accommodationEntities, warnings);
            }
        }

        // ------------------------------------------------------------------
        // Step 5. 방문일 예보 (예보 범위 밖이면 날씨 가감 없음)
        // ------------------------------------------------------------------
        Map<LocalDate, Boolean> badWeatherByDate = forecast(request, totalDays, dayCities, centers, mainCity);

        // ------------------------------------------------------------------
        // Step 6. 일정 엔진
        // ------------------------------------------------------------------
        PlanService.TripInput input = new PlanService.TripInput();
        input.request = request;
        input.candidates = allPlaces;
        input.arrivalAirport = arrivalAirport;
        input.departureAirport = departureAirport;
        input.lodgingByNight = lodgingByNight;
        input.dayCities = dayCities;
        input.badWeatherByDate = badWeatherByDate;

        PlanService.TripPlan tripPlan = planService.planTrip(input);
        warnings.addAll(tripPlan.getWarnings());

        boolean anyVisit = tripPlan.getDays().stream().flatMap(d -> d.getItems().stream())
                .anyMatch(i -> i.getType() == PlanService.SimulatedItinerary.Type.VISIT);
        if (!anyVisit) {
            throw new PlanGenerationException("현재 조건으로 생성 가능한 일정이 없습니다. 조건을 완화해주세요.\n" + String.join("\n", warnings));
        }

        // ------------------------------------------------------------------
        // Step 7. 응답 조립 + DB 저장
        // ------------------------------------------------------------------
        AiRouteResponse response = new AiRouteResponse();
        response.setReason("여행자의 취향과 물리적 한계를 고려하여 자체 최적화 알고리즘으로 구성된 일정입니다.");
        response.setTimeline(timelineAssembler.toTimeline(tripPlan.getDays(), request.getLanguage()));
        response.setWarnings(warnings);

        Plan saved = planPersistenceService.saveNewPlan(user, request, tripPlan.getDays(), accommodationEntities, response.getReason());
        response.setPlanId(saved.getId());
        return response;
    }

    // =====================================================================
    // 입력 검증
    // =====================================================================

    private void validate(PlanRequest request) {
        if (request == null) throw new IllegalArgumentException("요청 본문이 비어 있습니다.");
        if (request.getStartDate() == null || request.getEndDate() == null) {
            throw new IllegalArgumentException("여행 시작일과 종료일을 입력해주세요.");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("여행 종료일이 시작일보다 빠릅니다.");
        }
        if (planService.totalDays(request) > MAX_TRIP_DAYS) {
            throw new IllegalArgumentException("여행 기간은 최대 " + MAX_TRIP_DAYS + "일까지 지원합니다.");
        }

        List<String> cities = request.getCities() == null ? new ArrayList<>()
                : request.getCities().stream().filter(c -> c != null && !c.isBlank()).map(String::trim).distinct().collect(Collectors.toList());
        if (cities.isEmpty()) throw new IllegalArgumentException("여행 도시를 1개 이상 선택해주세요.");
        request.setCities(cities);

        if (request.getMealCount() != null) {
            int max = PlanService.maxMealCount(request);
            if (request.getMealCount() < 1 || request.getMealCount() > max) {
                throw new IllegalArgumentException("하루 식사 횟수는 1~" + max + "회 사이로 선택해주세요."
                        + (max < PlanService.MAX_MEALS_FOOD_THEME ? " (4회 이상은 맛집 테마를 선택했을 때만 가능합니다.)" : ""));
            }
        }

        if (request.getPreferredStartTime() != null && request.getPreferredStartTime().equals(request.getPreferredEndTime())) {
            throw new IllegalArgumentException("하루 일정 시작 시간과 종료 시간이 같습니다.");
        }

        if (request.getFixedSchedules() != null) {
            for (PlanRequest.FixedScheduleInput f : request.getFixedSchedules()) {
                if (f == null || f.getStartTime() == null || f.getEndTime() == null) {
                    throw new IllegalArgumentException("고정 일정에는 시작 시간과 종료 시간이 모두 필요합니다.");
                }
                if (!f.getEndTime().isAfter(f.getStartTime())) {
                    throw new IllegalArgumentException("고정 일정 [" + f.getName() + "]의 종료 시간이 시작 시간보다 빠릅니다.");
                }
                if (f.getDate() != null && (f.getDate().isBefore(request.getStartDate()) || f.getDate().isAfter(request.getEndDate()))) {
                    throw new IllegalArgumentException("고정 일정 [" + f.getName() + "]의 날짜가 여행 기간을 벗어납니다.");
                }
                if (f.getDayNumber() != null && (f.getDayNumber() < 1 || f.getDayNumber() > planService.totalDays(request))) {
                    throw new IllegalArgumentException("고정 일정 [" + f.getName() + "]의 일차가 여행 기간을 벗어납니다.");
                }
            }
            PlanValidationService.ValidationResult fixed = planValidationService.validateFixedSchedules(request.getFixedSchedules(), request.getStartDate());
            if (fixed.isWarning) throw new IllegalArgumentException(fixed.warningMessage);
        }

        if (request.getAccommodations() != null && !request.getAccommodations().isEmpty()) {
            for (PlanRequest.AccommodationInput acc : request.getAccommodations()) {
                if (acc == null || isBlank(acc.getName())) throw new IllegalArgumentException("숙소 이름을 입력해주세요.");
                // 날짜가 비어 있으면 여행 전체 기간 숙박으로 본다
                if (acc.getCheckIn() == null) acc.setCheckIn(request.getStartDate());
                if (acc.getCheckOut() == null) acc.setCheckOut(request.getEndDate());
                if (!acc.getCheckOut().isAfter(acc.getCheckIn()) && !request.getStartDate().equals(request.getEndDate())) {
                    throw new IllegalArgumentException("[" + acc.getName() + "] 숙소의 체크아웃이 체크인보다 빠르거나 같습니다.");
                }
            }
            PlanValidationService.ValidationResult accValidation = planValidationService.validateAccommodations(
                    request.getAccommodations(), request.getStartDate(), request.getEndDate());
            if (accValidation.isWarning) throw new IllegalArgumentException(accValidation.warningMessage);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // =====================================================================
    // 후보 수집
    // =====================================================================

    /**
     * DB 후보가 부족할 때만 구글에서 보충한다.
     *  (1) 요청 테마별로 후보가 너무 적으면 그 테마에 맞는 검색어로 수집
     *  (2) 전체 체류 시간 합이 여행 시간의 1.5배에 못 미치면 일반 명소 수집
     * 호출 횟수는 요청당 max(3, 테마 수)회로 제한한다.
     */
    private void supplementCandidates(PlanRequest request, int totalDays, List<Place> allPlaces) {
        List<String> cities = request.getCities();
        List<String> themes = ThemeVocabulary.normalizeAll(request.getThemes());
        int maxCalls = Math.max(3, themes.size());
        int calls = 0;

        int minPerTheme = Math.max(4, totalDays * 2);
        for (String theme : themes) {
            if (calls >= maxCalls) break;
            long count = allPlaces.stream()
                    .filter(planService::isVisitCandidate)
                    .filter(p -> planService.themesOf(p).contains(theme))
                    .count();
            if (count >= minPerTheme) continue;
            String city = cities.get(calls % cities.size());
            emergencyCollect(city, ThemeVocabulary.searchQueryFor(theme), theme, allPlaces);
            calls++;
        }

        int targetVolume = (int) (planService.calculateTotalPhysicalMinutes(request, totalDays) * 1.5);
        // 도시 1개당 평균 2일이 필요하다고 보고, 그보다 3일 이상 남으면 근교까지 넓힌다.
        boolean expandToSuburbs = totalDays - cities.size() * 2 >= 3 && !request.isExcludeSuburbs();
        List<String> generic = Arrays.asList("관광 명소", "인기 맛집", "랜드마크");

        while (calls < maxCalls) {
            int volume = allPlaces.stream()
                    .filter(planService::isVisitCandidate)
                    .mapToInt(p -> planService.calculateDwellTime(p, request))
                    .sum();
            if (volume >= targetVolume) break;

            String city = cities.get(calls % cities.size());
            String keyword = generic.get(calls % generic.size());
            emergencyCollect(city, expandToSuburbs ? keyword + " 근교" : keyword, null, allPlaces);
            calls++;
        }
    }

    private void emergencyCollect(String city, String query, String provisionalTheme, List<Place> allPlaces) {
        if (query == null) return;
        try {
            String formalizedCity = googleMapsService.getFormalizedJapanCity(city);
            List<double[]> grid = googleMapsService.getCityGrid(formalizedCity);
            if (grid.isEmpty()) return;
            double[] center = grid.get(0);

            List<Place> fetched = googleMapsService.searchNewPlacesFromGoogle(city, formalizedCity, center[0], center[1], center[2], query, true);
            Set<String> known = allPlaces.stream().map(Place::getPlaceId).collect(Collectors.toSet());

            for (Place p : fetched) {
                if (p.getPlaceId() == null || p.getPlaceId().isBlank() || known.contains(p.getPlaceId())) continue;
                if (p.getCity() == null) p.setCity(city);
                if (p.getCategory() == null) p.setCategory("관광지");
                // 테마는 12개 어휘에 있는 값만 "임시로" 넣는다. placeType/체류시간은 비워 두므로
                // 관리자 인리치먼트 배치가 나중에 리뷰 기반으로 다시 분류한다.
                // (이전에는 검색어 "필수 관광지" 같은 문자열이 그대로 테마로 저장됐다)
                if (p.getTheme() == null && provisionalTheme != null) p.setTheme(provisionalTheme);

                Place saved = planPersistenceService.upsertPlace(p);
                if (known.add(saved.getPlaceId())) allPlaces.add(saved);
            }
        } catch (Exception e) {
            System.out.println("긴급 수집 통신 에러(" + city + " / " + query + "): " + e.getMessage());
        }
    }

    // =====================================================================
    // 공항
    // =====================================================================

    private Place resolveAirport(String cityOrAirport, Map<String, double[]> centers, String fallbackCity,
                                 String label, List<String> warnings) {
        if (isBlank(cityOrAirport)) return null;

        double[] center = centers.get(cityOrAirport.trim());
        if (center == null) center = centers.get(fallbackCity);

        AirportDirectory.Resolution resolution = AirportDirectory.resolve(cityOrAirport, center);
        if (resolution == null) {
            warnings.add(label + " 공항을 '" + cityOrAirport + "'에서 찾지 못해 공항 이동 없이 계획했습니다. 공항 이름을 직접 입력해 주세요.");
            return null;
        }
        AirportDirectory.Airport airport = resolution.airport;
        if (resolution.ambiguous) {
            warnings.add(label + " 공항을 '" + airport.name + "'(으)로 가정했습니다. 다른 공항이면 "
                    + ("입국".equals(label) ? "inCity" : "outCity") + " 에 공항 이름을 넣어 주세요.");
        }

        // DB 에 같은 공항의 구글 장소가 있으면 그 좌표(실제 터미널)를 쓴다.
        Place fromDb = placeRepository.findByNameContaining("공항").stream()
                .filter(p -> "교통".equals(p.getCategory()) && p.getLatitude() != null && p.getLongitude() != null)
                .filter(p -> !p.getName().endsWith("역") && !p.getName().toLowerCase().contains("station"))   // "간사이공항역" 제외
                .filter(p -> DistanceUtil.calculateDistance(p.getLatitude(), p.getLongitude(), airport.latitude, airport.longitude) <= 5.0)
                .min(Comparator.comparingDouble(p -> DistanceUtil.calculateDistance(
                        p.getLatitude(), p.getLongitude(), airport.latitude, airport.longitude)))
                .orElse(null);
        if (fromDb != null) return fromDb;

        Place place = new Place();
        place.setPlaceId(airport.placeId());
        place.setName(airport.name);
        place.setCategory("교통");
        place.setCity(fallbackCity);
        place.setLatitude(airport.latitude);
        place.setLongitude(airport.longitude);
        return saveAnchor(place, "교통");
    }

    // =====================================================================
    // 숙소
    // =====================================================================

    /** 사용자가 입력한 숙소를 전부 좌표로 바꾸고, 체크인·체크아웃 날짜에 따라 밤마다 배정한다. */
    private void resolveUserAccommodations(PlanRequest request, int totalDays, Map<String, double[]> centers, String mainCity,
                                           Map<Integer, Place> lodgingByNight, List<Accommodation> entities, List<String> warnings) {
        for (PlanRequest.AccommodationInput acc : request.getAccommodations()) {
            Accommodation entity = new Accommodation();
            entity.setName(acc.getName());
            entity.setAddress(acc.getAddress());
            entity.setCheckIn(acc.getCheckIn());
            entity.setCheckOut(acc.getCheckOut());
            entities.add(entity);

            String query = isBlank(acc.getAddress()) ? acc.getName() + " " + mainCity : acc.getName() + " " + acc.getAddress();
            Place found = googleMapsService.findPlaceByText(query, request.getLanguage());
            if (found == null && !isBlank(acc.getAddress())) {
                found = googleMapsService.findPlaceByText(acc.getName() + " " + mainCity, request.getLanguage());
            }
            if (found == null) {
                warnings.add("숙소 '" + acc.getName() + "'의 위치를 찾지 못했습니다. 해당 기간은 숙소 출발/복귀 없이 계획했습니다.");
                continue;
            }
            found.setCity(nearestCity(found, centers, mainCity));
            Place lodging = saveAnchor(found, "숙소");
            entity.setPlaceId(lodging.getPlaceId());
            if (entity.getAddress() == null) entity.setAddress(lodging.getAddress());

            for (int night = 1; night < totalDays; night++) {
                LocalDate date = request.getStartDate().plusDays(night - 1L);
                if (!date.isBefore(acc.getCheckIn()) && date.isBefore(acc.getCheckOut())) {
                    lodgingByNight.put(night, lodging);
                }
            }
        }
    }

    /** 숙소 역제안: 그 날 도는 도시마다 중심에서 가장 가까운 추천 호텔 1곳 */
    private void suggestHotels(PlanRequest request, int totalDays, List<String> dayCities, Map<String, double[]> centers, String mainCity,
                               Map<Integer, Place> lodgingByNight, List<Accommodation> entities, List<String> warnings) {
        Map<String, Place> hotelByCity = new LinkedHashMap<>();
        Set<String> nightCities = new LinkedHashSet<>();
        for (int night = 1; night < totalDays; night++) {
            String city = dayCities.get(night - 1) != null ? dayCities.get(night - 1) : mainCity;
            nightCities.add(city);
        }

        for (String city : nightCities) {
            List<Place> hotels = googleMapsService.searchRecommendedHotels(city);
            if (hotels.isEmpty()) {
                warnings.add(city + " 추천 숙소를 찾지 못해 숙소 없이 계획했습니다.");
                continue;
            }
            double[] center = centers.get(city);
            Place best = center == null ? hotels.get(0) : hotels.stream()
                    .min(Comparator.comparingDouble(h -> DistanceUtil.calculateDistance(center[0], center[1], h.getLatitude(), h.getLongitude())))
                    .orElse(hotels.get(0));
            best.setCity(city);
            hotelByCity.put(city, saveAnchor(best, "숙소"));
        }

        // 같은 호텔에 연속으로 묵는 구간을 하나의 숙박 기록으로 묶는다.
        Place current = null;
        Accommodation entity = null;
        for (int night = 1; night < totalDays; night++) {
            String city = dayCities.get(night - 1) != null ? dayCities.get(night - 1) : mainCity;
            Place hotel = hotelByCity.get(city);
            if (hotel == null) {
                current = null;
                continue;
            }
            lodgingByNight.put(night, hotel);
            LocalDate date = request.getStartDate().plusDays(night - 1L);
            if (current == null || !current.getPlaceId().equals(hotel.getPlaceId())) {
                entity = new Accommodation();
                entity.setName(hotel.getName());
                entity.setAddress(hotel.getAddress());
                entity.setPlaceId(hotel.getPlaceId());
                entity.setCheckIn(date);
                entities.add(entity);
                current = hotel;
            }
            entity.setCheckOut(date.plusDays(1));
        }
    }

    /**
     * 숙소·공항을 저장하고 분류를 확정한다.
     * 같은 장소가 예전에 분류 없이(또는 다른 분류로) 저장돼 있으면 그 행의 분류를 바로잡는다.
     * 분류가 비어 있으면 숙소가 방문지로 뽑히거나, 재탐색에서 출발·도착 지점으로 인식되지 않는다.
     */
    private Place saveAnchor(Place fetched, String category) {
        fetched.setCategory(category);
        Place saved = planPersistenceService.upsertPlace(fetched);
        if (!category.equals(saved.getCategory())) {
            saved.setCategory(category);
            saved = placeRepository.save(saved);
        }
        return saved;
    }

    private String nearestCity(Place place, Map<String, double[]> centers, String fallback) {
        String best = fallback;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<String, double[]> e : centers.entrySet()) {
            double d = DistanceUtil.calculateDistance(place.getLatitude(), place.getLongitude(), e.getValue()[0], e.getValue()[1]);
            if (d < bestDistance) {
                bestDistance = d;
                best = e.getKey();
            }
        }
        return best;
    }

    // =====================================================================
    // 날씨
    // =====================================================================

    private Map<LocalDate, Boolean> forecast(PlanRequest request, int totalDays, List<String> dayCities,
                                             Map<String, double[]> centers, String mainCity) {
        Map<LocalDate, Boolean> result = new HashMap<>();
        Map<String, Map<LocalDate, Boolean>> byCity = new HashMap<>();
        for (int day = 1; day <= totalDays; day++) {
            String city = dayCities.get(day - 1) != null ? dayCities.get(day - 1) : mainCity;
            double[] center = centers.get(city);
            if (center == null) continue;
            Map<LocalDate, Boolean> cityForecast = byCity.computeIfAbsent(city,
                    c -> weatherService.getBadWeatherByDate(center[0], center[1], request.getStartDate(), request.getEndDate()));
            LocalDate date = request.getStartDate().plusDays(day - 1L);
            if (cityForecast.containsKey(date)) result.put(date, cityForecast.get(date));
        }
        return result;
    }
}
