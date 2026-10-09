package com.travel.planner.service;

import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.Place;
import com.travel.planner.util.AirportDirectory;
import com.travel.planner.util.DistanceUtil;
import com.travel.planner.util.OpeningHours;
import com.travel.planner.util.PlaceKind;
import com.travel.planner.util.ThemeVocabulary;
import com.travel.planner.util.TimeUtil;
import com.travel.planner.util.TravelTimeEstimator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 일정 엔진.
 *
 * 이전 구조는 ① 거리만 보고 하루치 장소를 고르는 단계와 ② 다른 시간 계산·다른 규칙으로 다시 걸러내는
 * 시뮬레이터가 따로 있어서, ②에서 탈락한 자리는 채워지지 않고(일정 조기 종료), ①에서 테마 점수가
 * 버려지고(테마 누락), 공항까지의 이동 시간은 어느 쪽도 확인하지 않았다(출국 시간 위반).
 *
 * 지금은 "선정"과 "검증"이 한 루프다. 매 순간 전체 후보 풀에서
 *   - 지금 출발해 영업시간 안에 방문을 끝낼 수 있고,
 *   - 다음 고정 일정에 늦지 않고,
 *   - 방문 뒤 도착 앵커(숙소/공항)에 마감 안에 돌아갈 수 있는
 * 후보만 남긴 뒤, (테마·인지도 점수 − 이동/대기 비용 + 테마 균형 보정)이 가장 높은 곳을 하나씩 넣는다.
 * 모든 시각은 자정 기준 분(int)으로 계산한다.
 */
@Service
public class PlanService {

    public static final String CATEGORY_FREE = "자유시간";
    public static final String CATEGORY_MEAL = "식사";
    public static final String CATEGORY_FIXED = "고정일정";

    /** 착륙 → 공항을 나서기까지 (입국심사·수하물) */
    static final int ARRIVAL_PROCESS_MIN = 90;
    /** 출국편 출발 몇 분 전까지 공항에 도착해야 하는가 */
    static final int DEPARTURE_CHECKIN_MIN = 120;
    /** 공항 좌표를 모를 때 가정하는 공항 이동 시간 */
    static final int UNKNOWN_AIRPORT_TRANSFER_MIN = 60;
    /** 이 이상 기다려야 하는 장소는 일단 다른 후보를 먼저 본다 */
    static final int MAX_WAIT_MIN = 45;
    /** 갈 곳이 없을 때 자유 시간을 넣고 기다려 줄 수 있는 최대 시간 */
    static final int MAX_DEFERRED_WAIT_MIN = 150;
    /** 숙소 복귀는 일과 종료 시각을 이만큼까지 넘겨도 허용 */
    static final int RETURN_GRACE_MIN = 45;
    static final int MEAL_PLACEHOLDER_MIN = 60;
    static final int MUST_EAT_MAX_TRAVEL_MIN = 30;

    static final double SEED_BONUS = 150.0;
    /** 이동 1분당 감점. 30분 이동 = 테마 일치 한 번(60점)과 맞먹게 해서 하루 동선이 권역 안에 모이도록 한다. */
    static final double TRAVEL_WEIGHT = 2.0;
    static final double WAIT_WEIGHT = 2.0;
    static final double SEED_DISTANCE_WEIGHT = 6.0;
    static final double CITY_MISMATCH_PENALTY = 80.0;
    static final double MIN_NET_UTILITY = -60.0;
    /** 도시 중심에서 이 거리를 넘는 장소는 후보에서 제외 */
    static final double MAX_DISTANCE_FROM_CENTER_KM = 50.0;
    /** 이 거리 안의 장소는 조건 없이 그날의 중심이 될 수 있다. 더 먼 곳은 주변에 갈 곳이 충분할 때만 */
    static final double NEAR_SEED_KM = 25.0;
    /** 하루 이동 시간이 이 값을 넘으면 warnings 로 알린다 */
    static final int LONG_TRAVEL_DAY_MIN = 240;

    /** 식사 시간대. earliest~giveUp 사이에 식당 방문을 시작할 수 있고, mustFrom 이후엔 식사가 최우선이다. */
    enum Meal {
        // 시간 순서대로 둔다 (엔진이 이 순서로 "다음 식사"를 찾는다)
        BREAKFAST("아침", 7 * 60, 8 * 60, 9 * 60 + 30, 10 * 60, false),
        LUNCH("점심", 11 * 60, 11 * 60 + 30, 13 * 60 + 30, 14 * 60 + 30, false),
        SNACK("간식", 14 * 60 + 30, 15 * 60, 16 * 60, 16 * 60 + 30, true),
        DINNER("저녁", 17 * 60, 18 * 60, 19 * 60 + 30, 20 * 60 + 30, false),
        LATE("야식", 20 * 60 + 30, 20 * 60 + 30, 21 * 60, 21 * 60 + 30, true);

        final String label;
        final int earliest;
        final int mustFrom;
        final int latestStart;
        final int giveUp;
        /** true 면 갈 식당이 없을 때 "자유 식사"를 넣지 않고 그냥 건너뛴다 (간식·야식) */
        final boolean optional;

        Meal(String label, int earliest, int mustFrom, int latestStart, int giveUp, boolean optional) {
            this.label = label;
            this.earliest = earliest;
            this.mustFrom = mustFrom;
            this.latestStart = latestStart;
            this.giveUp = giveUp;
            this.optional = optional;
        }
    }

    public static final int DEFAULT_MEAL_COUNT = 2;
    public static final int MAX_MEALS_FOOD_THEME = 5;
    public static final int MAX_MEALS_DEFAULT = 3;
    /** 앞 식사가 끝난 뒤 다음 식사까지 최소 간격(분) */
    static final int MIN_MEAL_GAP_MIN = 90;
    /** 간식·야식은 가볍게 먹는다고 보고 체류 시간을 줄인다 */
    static final int SNACK_DWELL_MAX_MIN = 45;
    static final int LATE_DWELL_MAX_MIN = 60;

    /** 하루 식사 횟수 상한: 맛집 테마를 골랐으면 5, 아니면 3 */
    public static int maxMealCount(PlanRequest request) {
        return ThemeVocabulary.normalizeAll(request.getThemes()).contains("맛집") ? MAX_MEALS_FOOD_THEME : MAX_MEALS_DEFAULT;
    }

    /** 요청한 하루 식사 횟수(없으면 2, 범위를 벗어나면 허용 범위로 맞춤) */
    static int mealCount(PlanRequest request) {
        Integer requested = request.getMealCount();
        if (requested == null) return DEFAULT_MEAL_COUNT;
        return Math.max(1, Math.min(maxMealCount(request), requested));
    }

    /** 횟수별 식사 구성. 1회는 {점심, 저녁} 중 그날 가능한 한 끼(저녁 우선)로 initMeals 에서 줄인다. */
    static EnumSet<Meal> mealsFor(int count) {
        EnumSet<Meal> meals = EnumSet.of(Meal.LUNCH, Meal.DINNER);
        if (count >= 3) meals.add(Meal.BREAKFAST);
        if (count >= 4) meals.add(Meal.SNACK);
        if (count >= 5) meals.add(Meal.LATE);
        return meals;
    }

    // =====================================================================================
    // 공개 자료구조
    // =====================================================================================

    /** 엔진 입력 */
    public static class TripInput {
        public PlanRequest request;
        /** 선택한 도시들의 장소 전체 (숙소·교통·제외 테마는 엔진이 걸러낸다) */
        public List<Place> candidates = new ArrayList<>();
        public Place arrivalAirport;
        public Place departureAirport;
        /** key = 일차, value = 그 날 밤 묵는 숙소 */
        public Map<Integer, Place> lodgingByNight = new HashMap<>();
        /** 일차별 방문 도시. null 이면 엔진이 배정한다. */
        public List<String> dayCities;
        /** 예보가 있는 날짜만. true = 비/눈 */
        public Map<LocalDate, Boolean> badWeatherByDate = new HashMap<>();
    }

    public static class TripPlan {
        private final List<DayPlan> days = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final Map<String, Integer> themeCounts = new LinkedHashMap<>();

        public List<DayPlan> getDays() { return days; }
        public List<String> getWarnings() { return warnings; }
        public Map<String, Integer> getThemeCounts() { return themeCounts; }
    }

    public static class DayPlan {
        private final int dayNumber;
        private final LocalDate date;
        private final String city;
        private final List<SimulatedItinerary> items = new ArrayList<>();

        DayPlan(int dayNumber, LocalDate date, String city) {
            this.dayNumber = dayNumber;
            this.date = date;
            this.city = city;
        }

        public int getDayNumber() { return dayNumber; }
        public LocalDate getDate() { return date; }
        public String getCity() { return city; }
        public List<SimulatedItinerary> getItems() { return items; }
    }

    /** 타임라인 한 줄 */
    public static class SimulatedItinerary {
        public enum Type { START, VISIT, FREE, MEAL, FIXED, END }

        private final Type type;
        private final Place place;       // FREE / MEAL / FIXED 는 null
        private final String title;      // place 가 없을 때의 표시 이름
        private final int startMin;
        private final Integer endMin;    // START / END 는 null
        private final int travelMinutes; // 직전 항목 → 이 항목 이동 시간
        private final Double latitude;
        private final Double longitude;

        SimulatedItinerary(Type type, Place place, String title, int startMin, Integer endMin,
                           int travelMinutes, Double latitude, Double longitude) {
            this.type = type;
            this.place = place;
            this.title = title;
            this.startMin = startMin;
            this.endMin = endMin;
            this.travelMinutes = travelMinutes;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public Type getType() { return type; }
        public Place getPlace() { return place; }
        public String getTitle() { return title; }
        public int getStartMin() { return startMin; }
        public Integer getEndMin() { return endMin; }
        public int getTravelMinutes() { return travelMinutes; }
        public Double getLatitude() { return latitude; }
        public Double getLongitude() { return longitude; }
        public String getTime() { return TimeUtil.format(startMin); }
        public String getEndTime() { return endMin == null ? null : TimeUtil.format(endMin); }
        public String getDisplayName() { return place != null ? place.getName() : title; }

        public String getCategory() {
            switch (type) {
                case FREE: return CATEGORY_FREE;
                case MEAL: return CATEGORY_MEAL;
                case FIXED: return CATEGORY_FIXED;
                default: return place != null ? place.getCategory() : null;
            }
        }
    }

    /** 순서가 정해진 하루 일정(reroute)의 검증 결과 */
    public static class SimulationResult {
        private boolean success;
        private String reason;
        private Place problemPlace;
        private List<SimulatedItinerary> validRoute = new ArrayList<>();

        public boolean isSuccess() { return success; }
        public String getReason() { return reason; }
        public Place getProblemPlace() { return problemPlace; }
        public List<SimulatedItinerary> getValidRoute() { return validRoute; }
        public void setSuccess(boolean s) { this.success = s; }
        public void setReason(String r) { this.reason = r; }
        public void setProblemPlace(Place p) { this.problemPlace = p; }
        public void setValidRoute(List<SimulatedItinerary> v) { this.validRoute = v; }
    }

    /** 하루의 시간 창·앵커·고정 일정 */
    public static class DayContext {
        int dayNumber;
        LocalDate date;
        /** 출발 앵커를 떠날 수 있는 가장 이른 시각 */
        int startMin;
        /** 방문을 끝내야 하는 시각 */
        int endMin;
        /** 도착 앵커(공항)에 반드시 도착해야 하는 시각. 없으면 null */
        Integer hardDeadlineMin;
        /** 1일차에 착륙 시각으로 startMin 이 정해졌는가 (더 일찍 출발할 수 없음) */
        boolean arrivalFixed;
        Place startAnchor;
        Place endAnchor;
        String city;
        boolean badWeather;
        String transportation;
        final List<FixedBlock> blocks = new ArrayList<>();

        public int getDayNumber() { return dayNumber; }
        public LocalDate getDate() { return date; }
        public int getStartMin() { return startMin; }
        public int getEndMin() { return endMin; }
        public Integer getHardDeadlineMin() { return hardDeadlineMin; }
        public Place getStartAnchor() { return startAnchor; }
        public Place getEndAnchor() { return endAnchor; }
        public String getCity() { return city; }
    }

    static class FixedBlock {
        String name;
        int start;
        int end;
        Double lat;
        Double lng;
        boolean explicitDay;
        boolean done;

        boolean hasLocation() { return lat != null && lng != null; }
    }

    // =====================================================================================
    // 요청 → 시간 창
    // =====================================================================================

    public int totalDays(PlanRequest request) {
        return (int) ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
    }

    private int dailyStartMin(PlanRequest request) {
        return request.getPreferredStartTime() != null ? TimeUtil.toMinutes(request.getPreferredStartTime()) : 9 * 60;
    }

    private int dailyEndMin(PlanRequest request) {
        int start = dailyStartMin(request);
        int end = request.getPreferredEndTime() != null ? TimeUtil.toMinutes(request.getPreferredEndTime()) : 22 * 60;
        if (end <= start) end += 1440;   // "09:00 ~ 01:00" 처럼 자정을 넘기는 선호 시간
        return end;
    }

    public static boolean isAirport(Place p) {
        if (p == null) return false;
        if (p.getPlaceId() != null && p.getPlaceId().startsWith(AirportDirectory.PLACE_ID_PREFIX)) return true;
        return "교통".equals(p.getCategory()) && p.getName() != null
                && (p.getName().contains("공항") || p.getName().toLowerCase().contains("airport"));
    }

    public static boolean isLodging(Place p) {
        return p != null && "숙소".equals(p.getCategory());
    }

    public static boolean isPseudoCategory(String category) {
        return CATEGORY_FREE.equals(category) || CATEGORY_MEAL.equals(category) || CATEGORY_FIXED.equals(category);
    }

    /**
     * 일차별 시간 창 계산.
     *  - 1일차: 착륙 시각 + 90분부터 움직일 수 있다 (실제 시각 "HH:mm" 을 주면 그대로 쓴다).
     *  - 마지막 날: 출국편 2시간 전까지 "공항에 도착"해야 한다. 공항까지의 이동 시간은
     *    후보를 넣을 때마다 따로 계산하므로 여기서 임의로 빼지 않는다.
     *  - 당일치기면 두 조건이 함께 적용된다.
     *  - "미정"이면 해당 제약 없이 선호 일과 시간을 그대로 쓴다.
     */
    public DayContext buildDayContext(PlanRequest request, int dayNumber, int totalDays, Place startAnchor, Place endAnchor) {
        DayContext ctx = new DayContext();
        ctx.dayNumber = dayNumber;
        ctx.date = request.getStartDate().plusDays(dayNumber - 1L);
        ctx.startAnchor = startAnchor;
        ctx.endAnchor = endAnchor;
        ctx.startMin = dailyStartMin(request);
        ctx.endMin = dailyEndMin(request);
        ctx.transportation = request.getTransportation();

        if (dayNumber == 1) {
            Integer landing = TimeUtil.parseFlightTime(request.getInTime());
            if (landing != null) {
                ctx.startMin = landing + ARRIVAL_PROCESS_MIN;
                if (!isAirport(startAnchor)) ctx.startMin += UNKNOWN_AIRPORT_TRANSFER_MIN;
                ctx.arrivalFixed = true;
            }
        }
        if (dayNumber == totalDays) {
            Integer takeoff = TimeUtil.parseFlightTime(request.getOutTime());
            if (takeoff != null) {
                int deadline = takeoff - DEPARTURE_CHECKIN_MIN;
                if (endAnchor != null) {
                    ctx.hardDeadlineMin = deadline;
                } else {
                    deadline -= UNKNOWN_AIRPORT_TRANSFER_MIN;
                }
                ctx.endMin = Math.min(ctx.endMin, deadline);
            }
        }

        if (request.getFixedSchedules() != null) {
            for (PlanRequest.FixedScheduleInput f : request.getFixedSchedules()) {
                if (f == null || f.getStartTime() == null || f.getEndTime() == null) continue;
                boolean explicit = f.getDate() != null || f.getDayNumber() != null;
                boolean applies = !explicit
                        || (f.getDate() != null && f.getDate().equals(ctx.date))
                        || (f.getDayNumber() != null && f.getDayNumber() == dayNumber);
                if (!applies) continue;

                FixedBlock b = new FixedBlock();
                b.name = f.getName() != null && !f.getName().isBlank() ? f.getName() : "고정 일정";
                b.start = TimeUtil.toMinutes(f.getStartTime());
                b.end = TimeUtil.toMinutes(f.getEndTime());
                if (b.end <= b.start) continue;
                b.lat = f.getLatitude();
                b.lng = f.getLongitude();
                b.explicitDay = explicit;
                ctx.blocks.add(b);
            }
            ctx.blocks.sort(Comparator.comparingInt(b -> b.start));
        }
        return ctx;
    }

    /** 여행 전체에서 실제로 돌아다닐 수 있는 시간(분). 후보 풀이 충분한지 판단하는 데 쓴다. */
    public int calculateTotalPhysicalMinutes(PlanRequest request, int totalDays) {
        int total = 0;
        for (int day = 1; day <= totalDays; day++) {
            total += getDailyPhysicalMinutes(request, day, totalDays);
        }
        return total;
    }

    public int getDailyPhysicalMinutes(PlanRequest request, int day, int totalDays) {
        DayContext ctx = buildDayContext(request, day, totalDays, null, null);
        int minutes = ctx.endMin - ctx.startMin;
        for (FixedBlock b : ctx.blocks) {
            int overlap = Math.min(b.end, ctx.endMin) - Math.max(b.start, ctx.startMin);
            if (overlap > 0) minutes -= overlap;
        }
        return Math.max(0, minutes);
    }

    public int calculateBufferTime(PlanRequest request) {
        List<String> themes = ThemeVocabulary.normalizeAll(request.getThemes());
        boolean isTight = themes.contains("액티비티") || themes.contains("쇼핑");
        boolean isRelaxed = themes.contains("힐링");
        if (isRelaxed || isFamily(request)) return 20;
        if (isTight) return 10;
        return 15;
    }

    private static boolean isFamily(PlanRequest request) {
        String c = request.getCompanion();
        return c != null && (c.contains("가족") || c.contains("부모") || c.contains("아이") || c.contains("유아"));
    }

    public int calculateDwellTime(Place p, PlanRequest request) {
        if (isPseudoCategory(p.getCategory())) return 120;

        PlaceKind kind = PlaceKind.of(p);
        Integer recommended = p.getRecommendedDuration();
        boolean hasRecommended = recommended != null && recommended > 0;
        int time;

        switch (kind) {
            case THEME_PARK:
                time = Math.max(hasRecommended ? recommended : 0, 480);
                break;
            case RESTAURANT:
                time = clamp(hasRecommended ? recommended : 60, 45, 120);
                break;
            case CAFE:
                time = clamp(hasRecommended ? recommended : 45, 30, 90);
                break;
            case BAR:
                time = clamp(hasRecommended ? recommended : 75, 45, 120);
                break;
            case SHOPPING:
                time = clamp(hasRecommended ? recommended : 90, 30, 180);
                break;
            default:
                time = clamp(hasRecommended ? recommended : defaultAttractionDwell(p), 20, 300);
        }

        List<String> themes = ThemeVocabulary.normalizeAll(request.getThemes());
        if (kind != PlaceKind.THEME_PARK && (isFamily(request) || themes.contains("힐링"))) {
            time = (int) (time * 1.2);
        }
        return TimeUtil.roundUpTo5(time);
    }

    /** 체류 시간 정보가 없는 관광지의 기본값. 동네 공원·광장에 90분씩 잡히지 않도록 이름으로 나눈다. */
    private static int defaultAttractionDwell(Place p) {
        String name = p.getName() == null ? "" : p.getName().toLowerCase();
        for (String k : new String[]{"공원", "park", "정원", "garden", "광장", "거리", "신사", "shrine", "전망대"}) {
            if (name.contains(k)) return 50;
        }
        for (String k : new String[]{"온천", "温泉", "스파", "박물관", "미술관", "museum", "수족관", "동물원"}) {
            if (name.contains(k)) return 90;
        }
        return 70;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // =====================================================================================
    // 후보 풀
    // =====================================================================================

    static class Cand {
        Place place;
        PlaceKind kind;
        double lat;
        double lng;
        Set<String> themes = new HashSet<>();
        boolean explicitThemes;
        List<String> matched = new ArrayList<>();
        double baseScore;
        double distFromCenter;
        /** 도심에서 멀고(25km 초과) 주변 5km 안에 함께 볼 곳이 3곳 미만인 외딴 장소 */
        boolean remoteLone;
        String brandKey;
        int dwell;
        final Map<LocalDate, List<int[]>> intervalCache = new HashMap<>();
    }

    static class TripState {
        PlanRequest request;
        List<String> themes;                 // 정규화된 요청 테마
        List<Cand> pool = new ArrayList<>();
        Map<String, double[]> centers = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Set<String> visitedBrands = new HashSet<>();
        Map<String, Integer> themeCounts = new LinkedHashMap<>();
        int buffer;
        boolean family;
        boolean foodLover;
        boolean cafeLover;
        boolean shoppingLover;
        boolean nightViewLover;
        /** 카페 추천 받지 않기 (카페 테마를 고르지 않았을 때만) */
        boolean noCafe;
        /** 하루 식사 구성 */
        int mealCount = DEFAULT_MEAL_COUNT;
        EnumSet<Meal> meals = EnumSet.of(Meal.LUNCH, Meal.DINNER);
        final Map<Meal, Integer> mealActiveDays = new EnumMap<>(Meal.class);
        final List<Integer> optionalMealSkippedDays = new ArrayList<>();
        /** 외딴 장소 한 곳을 중심으로 삼은 날 수 (요청 테마를 시내에서 채울 수 없을 때만, 여행당 제한) */
        int remoteDaysUsed;
        int maxRemoteDays;
    }

    /** 도시별 중심 좌표(그 도시 장소들의 위·경도 중앙값). 이전에는 places.get(0) 을 기준점으로 써서 엉뚱했다. */
    public Map<String, double[]> cityCenters(List<Place> places, List<String> cities) {
        Map<String, double[]> centers = new LinkedHashMap<>();
        if (cities == null) return centers;
        for (String city : cities) {
            List<Place> inCity = places.stream()
                    .filter(p -> city.equals(p.getCity()))
                    .filter(p -> p.getLatitude() != null && p.getLongitude() != null)
                    .filter(p -> !(p.getLatitude() == 0.0 && p.getLongitude() == 0.0))
                    .filter(p -> !isAirport(p) && !isPseudoCategory(p.getCategory()))
                    .collect(Collectors.toList());
            if (inCity.isEmpty()) continue;
            double[] lats = inCity.stream().mapToDouble(Place::getLatitude).sorted().toArray();
            double[] lngs = inCity.stream().mapToDouble(Place::getLongitude).sorted().toArray();
            centers.put(city, new double[]{lats[lats.length / 2], lngs[lngs.length / 2]});
        }
        return centers;
    }

    /** 엔진이 방문 후보로 쓸 수 있는 장소인가 (숙소·교통·공항·더미 제외). */
    public boolean isVisitCandidate(Place p) {
        if (p == null || p.getPlaceId() == null || p.getName() == null) return false;
        if (p.getLatitude() == null || p.getLongitude() == null) return false;
        if (p.getLatitude() == 0.0 && p.getLongitude() == 0.0) return false;
        String category = p.getCategory();
        if ("숙소".equals(category) || "교통".equals(category) || isPseudoCategory(category)) return false;
        if (p.getPlaceId().startsWith("DUMMY_") || p.getPlaceId().startsWith(AirportDirectory.PLACE_ID_PREFIX)) return false;
        String name = p.getName();
        if (name.contains("공항") || name.toLowerCase().contains("airport")) return false;
        // 분류가 비어 있거나 관광지로 잘못 들어간 호텔·역이 "방문지"로 뽑히지 않게 이름으로 한 번 더 거른다.
        boolean uncertainCategory = category == null || category.isBlank() || "관광지".equals(category);
        return !(uncertainCategory && (PlaceKind.looksLikeLodging(name) || PlaceKind.looksLikeStation(name)));
    }

    /** 장소가 요청 테마에 해당하는가. DB 테마가 비어 있으면 카테고리·이름으로 추정한 값을 쓴다. */
    public Set<String> themesOf(Place p) {
        Set<String> explicit = ThemeVocabulary.explicitThemes(p);
        return explicit.isEmpty() ? ThemeVocabulary.inferredThemes(p) : explicit;
    }

    /** 요청한 식사 횟수를 다 채우지 못한 이유를 알린다. */
    private void addMealWarnings(TripState st, List<String> warnings) {
        for (Meal m : st.meals) {
            boolean extra = m == Meal.BREAKFAST || m == Meal.SNACK || m == Meal.LATE;
            if (extra && st.mealActiveDays.getOrDefault(m, 0) == 0) {
                warnings.add(String.format("'%s'(%s~%s)은 하루 일정 시간 안에 들어가지 않아 넣지 못했습니다. 일정 시작·종료 시간을 조정하면 포함됩니다.",
                        m.label, TimeUtil.format(m.mustFrom), TimeUtil.format(m.mustFrom + MEAL_PLACEHOLDER_MIN)));
            }
        }
        if (!st.optionalMealSkippedDays.isEmpty()) {
            String days = st.optionalMealSkippedDays.stream().map(d -> "Day " + d).collect(Collectors.joining(", "));
            warnings.add("간식·야식 시간대에 갈 수 있는 식당이 없어 " + days + " 은(는) 요청한 식사 횟수보다 적게 넣었습니다.");
        }
    }

    private TripState buildState(TripInput in) {
        PlanRequest req = in.request;
        TripState st = new TripState();
        st.request = req;
        st.themes = ThemeVocabulary.normalizeAll(req.getThemes());
        st.buffer = calculateBufferTime(req);
        st.family = isFamily(req);
        st.foodLover = st.themes.contains("맛집");
        st.cafeLover = st.themes.contains("카페");
        st.shoppingLover = st.themes.contains("쇼핑");
        st.nightViewLover = st.themes.contains("야경");
        st.noCafe = Boolean.TRUE.equals(req.getExcludeCafe()) && !st.cafeLover;
        st.mealCount = mealCount(req);
        st.meals = mealsFor(st.mealCount);
        for (String t : st.themes) st.themeCounts.put(t, 0);

        st.centers = cityCenters(in.candidates, req.getCities());
        List<String> excluded = ThemeVocabulary.normalizeAll(req.getExcludedThemes());
        List<String> excludedRaw = req.getExcludedThemes() != null ? req.getExcludedThemes() : Collections.emptyList();

        Set<String> anchorIds = new HashSet<>();
        if (in.arrivalAirport != null) anchorIds.add(in.arrivalAirport.getPlaceId());
        if (in.departureAirport != null) anchorIds.add(in.departureAirport.getPlaceId());
        for (Place lodging : in.lodgingByNight.values()) {
            if (lodging != null) anchorIds.add(lodging.getPlaceId());
        }

        Set<String> seen = new HashSet<>();
        for (Place p : in.candidates) {
            if (!isVisitCandidate(p) || anchorIds.contains(p.getPlaceId()) || !seen.add(p.getPlaceId())) continue;

            Cand c = new Cand();
            c.place = p;
            c.kind = PlaceKind.of(p);
            c.lat = p.getLatitude();
            c.lng = p.getLongitude();

            Set<String> explicit = ThemeVocabulary.explicitThemes(p);
            c.explicitThemes = !explicit.isEmpty();
            c.themes = c.explicitThemes ? explicit : ThemeVocabulary.inferredThemes(p);

            // 제외 테마: 테마 또는 카테고리에 걸리면 후보에서 뺀다.
            boolean isExcluded = c.themes.stream().anyMatch(excluded::contains)
                    || excludedRaw.stream().anyMatch(ex -> ex != null && !ex.isBlank()
                    && p.getCategory() != null && p.getCategory().contains(ex));
            if (isExcluded) continue;
            if (st.family && c.kind == PlaceKind.BAR) continue;
            if (st.noCafe && c.kind == PlaceKind.CAFE) continue;

            double[] center = st.centers.get(p.getCity());
            double distFromCenter = center == null ? 0.0 : DistanceUtil.calculateDistance(center[0], center[1], c.lat, c.lng);
            if (req.isExcludeSuburbs() && distFromCenter > 20.0) continue;
            // "근교"라도 도심에서 직선 50km 를 넘는 곳은 당일로 다녀오기 어렵다 (다른 현의 온천 등이 섞여 들어오는 것 방지)
            if (distFromCenter > MAX_DISTANCE_FROM_CENTER_KM) continue;
            c.distFromCenter = distFromCenter;

            for (String t : st.themes) {
                if (c.themes.contains(t)) c.matched.add(t);
            }
            c.baseScore = baseScore(c, st, distFromCenter);
            c.brandKey = brandKey(p.getName());
            c.dwell = calculateDwellTime(p, req);
            st.pool.add(c);
        }
        // HashMap 순회 순서에 따라 결과가 달라지지 않도록 고정
        st.pool.sort(Comparator.comparing(c -> c.place.getPlaceId()));
        for (Cand c : st.pool) c.remoteLone = !seedEligible(c, st);
        int days = totalDays(req);
        st.maxRemoteDays = days < 3 ? 0 : (days < 7 ? 1 : 2);
        return st;
    }

    /**
     * 날짜와 무관한 기본 점수.
     * 날씨는 "요청 시점의 오늘 날씨"가 아니라 방문일 예보로, 일차별로만 가감한다(evaluate 참고).
     */
    private double baseScore(Cand c, TripState st, double distFromCenterKm) {
        double score = 50.0;

        if (!c.matched.isEmpty()) {
            score += (c.explicitThemes ? 60.0 : 45.0) + 25.0 * (c.matched.size() - 1);
        }

        Place p = c.place;
        if (p.getUserRatingCount() != null && p.getUserRatingCount() > 0) {
            score += Math.min(30.0, 6.0 * Math.log10(p.getUserRatingCount() + 1.0));
        }
        if (p.getRating() != null && p.getRating() > 0) {
            score += Math.max(-15.0, Math.min(10.0, (p.getRating() - 4.0) * 15.0));
        }

        if (st.family) {
            if (c.kind == PlaceKind.ATTRACTION) score += 15.0;
            if (c.themes.contains("액티비티") && c.kind != PlaceKind.THEME_PARK) score -= 30.0;
        }

        // 도심에서 먼 곳은 약하게만 감점한다. (자연·온천은 외곽이 정상)
        double allowed = (c.themes.contains("자연") || c.themes.contains("온천")) ? 25.0 : 10.0;
        if (distFromCenterKm > allowed) {
            score -= Math.min(45.0, (distFromCenterKm - allowed) * 1.5);
        }
        return score;
    }

    /** "돈키호테 도톤보리점" → "돈키호테". 같은 체인의 다른 지점을 또 넣지 않기 위한 키. */
    private static String brandKey(String name) {
        if (name == null) return null;
        String[] tokens = name.trim().split("\\s+");
        if (tokens.length < 2) return null;
        String last = tokens[tokens.length - 1];
        if (!(last.endsWith("점") || last.endsWith("店")) || last.length() < 2) return null;
        String key = String.join(" ", java.util.Arrays.copyOf(tokens, tokens.length - 1)).trim();
        return key.length() >= 2 ? key : null;
    }

    // =====================================================================================
    // 일차별 도시 배정
    // =====================================================================================

    /**
     * 여러 도시를 고른 경우 어느 날 어느 도시를 돌지 정한다.
     * 도시별 일수는 균등 분배하고, 각 날의 앵커(공항·숙소)와 가까운 도시를 우선 배정한다.
     * 도시 수가 일수보다 많으면 null(제한 없음)로 둔다.
     */
    public List<String> assignDayCities(PlanRequest request, int totalDays, Map<String, double[]> centers,
                                        Place arrivalAirport, Place departureAirport, Map<Integer, Place> lodgingByNight) {
        List<String> result = new ArrayList<>(Collections.nCopies(totalDays, (String) null));
        List<String> cities = request.getCities() == null ? new ArrayList<>()
                : request.getCities().stream().filter(centers::containsKey).distinct().collect(Collectors.toList());
        if (cities.isEmpty()) return result;
        if (cities.size() == 1) {
            Collections.fill(result, cities.get(0));
            return result;
        }
        if (cities.size() > totalDays) return result;

        int n = cities.size();
        int[] quota = new int[n];
        for (int i = 0; i < n; i++) quota[i] = totalDays / n + (i < totalDays % n ? 1 : 0);

        double[][] cost = new double[totalDays][n];
        for (int d = 0; d < totalDays; d++) {
            int day = d + 1;
            Place start = day == 1 ? arrivalAirport : lodgingByNight.get(day - 1);
            Place end = day == totalDays ? departureAirport : lodgingByNight.get(day);
            // 같은 거리라도 입·출국일처럼 쓸 수 있는 시간이 짧은 날일수록 먼 도시가 더 불리하다.
            double available = Math.max(60, getDailyPhysicalMinutes(request, day, totalDays));
            for (int c = 0; c < n; c++) {
                double[] center = centers.get(cities.get(c));
                cost[d][c] = (anchorDistance(start, center) + anchorDistance(end, center)) / available;
            }
        }

        int[] assigned = new int[totalDays];
        java.util.Arrays.fill(assigned, -1);
        for (int round = 0; round < totalDays; round++) {
            int bestDay = -1;
            int bestCity = -1;
            double bestRegret = -1.0;
            for (int d = 0; d < totalDays; d++) {
                if (assigned[d] >= 0) continue;
                int first = -1;
                int second = -1;
                double firstCost = 0.0;
                double secondCost = 0.0;
                for (int c = 0; c < n; c++) {
                    if (quota[c] <= 0) continue;
                    // 비용이 같으면 전날과 같은 도시를 골라 같은 도시가 연속된 날에 오도록 한다.
                    double value = cost[d][c] - ((d > 0 && assigned[d - 1] == c) ? 1e-6 : 0.0);
                    if (first < 0 || value < firstCost) {
                        second = first;
                        secondCost = firstCost;
                        first = c;
                        firstCost = value;
                    } else if (second < 0 || value < secondCost) {
                        second = c;
                        secondCost = value;
                    }
                }
                if (first < 0) continue;
                // regret = 이 날을 차선 도시에 배정했을 때의 손해. 손해가 큰 날부터 확정한다.
                double regret = second < 0 ? 0.0 : secondCost - firstCost;
                if (regret > bestRegret + 1e-9) {
                    bestRegret = regret;
                    bestDay = d;
                    bestCity = first;
                }
            }
            if (bestDay < 0) break;
            assigned[bestDay] = bestCity;
            quota[bestCity]--;
        }
        for (int d = 0; d < totalDays; d++) {
            if (assigned[d] >= 0) result.set(d, cities.get(assigned[d]));
        }
        return result;
    }

    private static double anchorDistance(Place anchor, double[] center) {
        if (anchor == null || center == null || anchor.getLatitude() == null || anchor.getLongitude() == null) return 0.0;
        return DistanceUtil.calculateDistance(anchor.getLatitude(), anchor.getLongitude(), center[0], center[1]);
    }

    // =====================================================================================
    // 여행 전체 계획
    // =====================================================================================

    public TripPlan planTrip(TripInput in) {
        PlanRequest req = in.request;
        int totalDays = totalDays(req);
        TripState st = buildState(in);
        TripPlan plan = new TripPlan();

        List<String> dayCities = in.dayCities != null && in.dayCities.size() == totalDays
                ? in.dayCities
                : assignDayCities(req, totalDays, st.centers, in.arrivalAirport, in.departureAirport, in.lodgingByNight);

        boolean warnedUndatedFixed = false;
        for (int day = 1; day <= totalDays; day++) {
            Place start = day == 1 ? in.arrivalAirport : in.lodgingByNight.get(day - 1);
            if (start == null && day == 1) start = in.lodgingByNight.get(1);   // 입국 공항을 모르면 숙소에서 출발
            Place end = day == totalDays ? in.departureAirport : in.lodgingByNight.get(day);

            DayContext ctx = buildDayContext(req, day, totalDays, start, end);
            ctx.city = dayCities.get(day - 1);
            ctx.badWeather = Boolean.TRUE.equals(in.badWeatherByDate.get(ctx.date));

            if (!warnedUndatedFixed && ctx.blocks.stream().anyMatch(b -> !b.explicitDay) && totalDays > 1) {
                plan.warnings.add("날짜(date)나 일차(dayNumber)가 없는 고정 일정은 매일 적용했습니다. 특정 날에만 넣으려면 date 또는 dayNumber 를 함께 보내 주세요.");
                warnedUndatedFixed = true;
            }
            plan.days.add(planDay(ctx, st, plan.warnings));
        }

        plan.themeCounts.putAll(st.themeCounts);
        addMealWarnings(st, plan.warnings);
        for (String theme : st.themes) {
            if (st.themeCounts.getOrDefault(theme, 0) > 0) continue;
            boolean anyCandidate = st.pool.stream().anyMatch(c -> c.matched.contains(theme));
            if (anyCandidate) {
                plan.warnings.add("'" + theme + "' 테마 장소는 후보에 있었지만 영업시간·이동 시간 제약 때문에 일정에 넣지 못했습니다.");
            } else {
                plan.warnings.add("'" + theme + "' 테마에 해당하는 장소 데이터가 선택한 도시에 없습니다. 장소 수집/테마 인리치먼트를 먼저 실행해 주세요.");
            }
        }
        return plan;
    }

    // =====================================================================================
    // 하루 계획 (선정 + 검증 단일 루프)
    // =====================================================================================

    private static class DayState {
        int cur;
        Double locLat;
        Double locLng;
        final List<SimulatedItinerary> items = new ArrayList<>();
        final Set<Meal> mealsDone = EnumSet.noneOf(Meal.class);
        final Map<String, Integer> dayThemeCounts = new HashMap<>();
        int visitCount;
        int cafes;
        int lastCafeEnd = -1000;
        int lastMealEnd = -1000;
        int bars;
        int shopping;
        boolean themeParkDone;
        PlaceKind lastKind;
        Cand seed;
        /** 고정 일정 등으로 그날의 중심 권역을 벗어나면 true: 이후에는 현재 위치 기준으로만 고른다 */
        boolean leftSeedArea;
        /** 직전 항목이 방문이면 그 뒤에 더해 둔 여유 시간(분). 숙소로 돌아갈 때는 이 여유를 붙이지 않는다. */
        int bufferPending;
        /** 출발 앵커를 떠나는 시각 (첫 방문지가 늦게 열면 숙소에서 늦게 나온다) */
        int startDeparture;
    }

    private static class Eval {
        Cand cand;
        int travel;
        int start;
        int end;
        int wait;
        int dwell;
        Meal fills;
        double utility;
        double waitPenalty;
    }

    private DayPlan planDay(DayContext ctx, TripState st, List<String> warnings) {
        DayPlan dayPlan = new DayPlan(ctx.dayNumber, ctx.date, ctx.city);
        DayState ds = new DayState();
        if (ctx.startAnchor != null) {
            ds.locLat = ctx.startAnchor.getLatitude();
            ds.locLng = ctx.startAnchor.getLongitude();
        }

        dropImpossibleBlocks(ctx, warnings);   // 이른 고정 일정이 있으면 ctx.startMin 이 앞당겨질 수 있다
        ds.cur = ctx.startMin;
        ds.startDeparture = ctx.startMin;
        int visitLimit = visitLimit(ctx);
        initMeals(ctx, ds, st, visitLimit);
        ds.seed = visitLimit - ctx.startMin >= 60 ? chooseSeed(ctx, st) : null;
        boolean remoteSeed = ds.seed != null && !seedEligible(ds.seed, st);
        if (remoteSeed) st.remoteDaysUsed++;

        for (int guard = 0; guard < 80; guard++) {
            for (Meal m : Meal.values()) {
                if (!ds.mealsDone.contains(m) && ds.cur >= m.giveUp) ds.mealsDone.add(m);
            }

            // (a) 고정 일정 시각이 되면 그것부터
            if (enterFixedBlockIfDue(ctx, ds, warnings)) continue;

            Meal pending = pendingMeal(ds);
            boolean mustEat = pending != null && ds.cur >= mustFrom(ds, pending);

            // (b) 지금 바로 갈 수 있는 최선의 후보
            Eval best = pickBest(ctx, ds, st, false);
            if (best != null) {
                commitVisit(ctx, ds, st, best);
                continue;
            }

            FixedBlock nextBlock = nextBlock(ctx);

            // (c) 식사 시간인데 갈 수 있는 식당이 없으면 자유 식사 60분
            if (mustEat) {
                if (pending.optional) {
                    // 간식·야식: 그 시간에 갈 식당이 없으면 자리만 차지하는 "자유 식사"를 넣지 않고 건너뛴다
                    ds.mealsDone.add(pending);
                    if (!st.optionalMealSkippedDays.contains(ctx.dayNumber)) st.optionalMealSkippedDays.add(ctx.dayNumber);
                    continue;
                }
                if (pending == Meal.DINNER && nextBlock == null && isLodging(ctx.endAnchor) && !hasRestaurantNearby(ds, st)) {
                    dinnerNearLodging(ctx, ds);
                    continue;
                }
                if (addMealPlaceholder(ctx, ds, pending, nextBlock)) continue;
                if (nextBlock != null) {
                    jumpToBlock(ctx, ds, nextBlock);
                    continue;
                }
                ds.mealsDone.add(pending);
                continue;
            }

            // (d) 조금 기다리면 열리는 곳(야경 명소, 늦게 여는 가게)이 있으면 자유 시간을 두고 간다
            Eval deferred = pickBest(ctx, ds, st, true);
            if (deferred != null) {
                commitVisit(ctx, ds, st, deferred);
                continue;
            }

            // 그날의 중심 권역에서 더 갈 곳이 없으면 권역 제한을 풀고 다시 고른다 (오후가 통째로 비는 것 방지)
            if (ds.seed != null && !ds.leftSeedArea && st.visited.contains(ds.seed.place.getPlaceId())) {
                ds.leftSeedArea = true;
                continue;
            }

            // (e) 더 넣을 곳이 없으면 다음 예정(고정 일정 또는 식사 시간)까지 시간을 흘려보낸다
            Integer blockDepart = nextBlock == null ? null : nextBlock.start - travelToBlock(ctx, ds, nextBlock);
            Integer mealAt = (pending != null && ds.cur < mustFrom(ds, pending)) ? mustFrom(ds, pending) : null;
            if (nextBlock != null && (mealAt == null || blockDepart <= mealAt)) {
                jumpToBlock(ctx, ds, nextBlock);
                continue;
            }
            if (mealAt != null) {
                if (mealAt - ds.cur >= 120) {
                    warnings.add(String.format("Day %d: %s~%s 에 넣을 수 있는 장소가 부족해 자유 시간으로 두었습니다.",
                            ctx.dayNumber, TimeUtil.format(ds.cur), TimeUtil.format(mealAt)));
                }
                addFree(ds, ds.cur, mealAt);
                ds.cur = mealAt;
                continue;
            }
            break;
        }

        if (remoteSeed && st.visited.contains(ds.seed.place.getPlaceId())) {
            String theme = ds.seed.matched.stream()
                    .min(Comparator.comparingInt(t -> st.themeCounts.getOrDefault(t, 0))).orElse("요청");
            warnings.add(String.format("Day %d: 시내에 '%s' 테마 장소가 부족해 도심에서 약 %dkm 떨어진 '%s'을(를) 넣었습니다. 이동 시간이 깁니다.",
                    ctx.dayNumber, theme, Math.round(ds.seed.distFromCenter), ds.seed.place.getName()));
        }
        finishDay(ctx, ds, dayPlan, warnings);
        return dayPlan;
    }

    /** 마지막 방문이 끝나야 하는 대략적인 한계 시각 (식사 계획 가능 여부 판단용) */
    private int visitLimit(DayContext ctx) {
        if (ctx.hardDeadlineMin == null || ctx.endAnchor == null) return ctx.endMin;
        int back = UNKNOWN_AIRPORT_TRANSFER_MIN;
        if (ctx.startAnchor != null) back = travel(ctx, ctx.startAnchor.getLatitude(), ctx.startAnchor.getLongitude(),
                ctx.endAnchor.getLatitude(), ctx.endAnchor.getLongitude());
        return Math.min(ctx.endMin, ctx.hardDeadlineMin - back);
    }

    private void initMeals(DayContext ctx, DayState ds, TripState st, int visitLimit) {
        for (Meal m : Meal.values()) {
            boolean activeBefore = ctx.startMin < m.giveUp - 30;
            boolean activeAfter = visitLimit >= m.mustFrom + MEAL_PLACEHOLDER_MIN;
            if (!st.meals.contains(m) || !activeBefore || !activeAfter) ds.mealsDone.add(m);
        }
        // 하루 1회: 저녁을 우선하고, 저녁을 먹을 수 없는 날(출국일 등)에만 점심으로 대신한다
        if (st.mealCount == 1 && !ds.mealsDone.contains(Meal.DINNER)) ds.mealsDone.add(Meal.LUNCH);
        for (Meal m : Meal.values()) {
            if (!ds.mealsDone.contains(m)) st.mealActiveDays.merge(m, 1, Integer::sum);
        }
    }

    /** 앞 식사와의 간격을 반영한 "이때부터는 식사가 우선" 시각 */
    private static int mustFrom(DayState ds, Meal m) {
        return Math.min(m.latestStart, Math.max(m.mustFrom, ds.lastMealEnd + MIN_MEAL_GAP_MIN));
    }

    /** 앞 식사와의 간격을 반영한 "이때부터 식당에 갈 수 있음" 시각 */
    private static int earliest(DayState ds, Meal m) {
        return Math.min(m.latestStart, Math.max(m.earliest, ds.lastMealEnd + MIN_MEAL_GAP_MIN));
    }

    private void dropImpossibleBlocks(DayContext ctx, List<String> warnings) {
        for (FixedBlock b : ctx.blocks) {
            int travelFromStart = (b.hasLocation() && ctx.startAnchor != null)
                    ? travel(ctx, ctx.startAnchor.getLatitude(), ctx.startAnchor.getLongitude(), b.lat, b.lng) : 0;
            if (!ctx.arrivalFixed && b.start - travelFromStart < ctx.startMin) {
                // 선호 시작 시각보다 이른 고정 일정이 있으면 그날은 그만큼 일찍 출발한다.
                ctx.startMin = Math.max(0, b.start - travelFromStart);
            }
            // 입국일에는 착륙 전에 시작하는 고정 일정에 참석할 수 없다.
            boolean beforeStart = ctx.arrivalFixed ? b.start - travelFromStart < ctx.startMin : b.end <= ctx.startMin;
            boolean afterEnd = ctx.hardDeadlineMin != null ? b.end > ctx.hardDeadlineMin : b.start >= ctx.endMin + RETURN_GRACE_MIN;
            if (beforeStart || afterEnd) {
                b.done = true;
                if (b.explicitDay) {
                    warnings.add(String.format("Day %d 고정 일정 '%s'(%s~%s)은 입·출국 시간과 겹쳐 넣을 수 없었습니다.",
                            ctx.dayNumber, b.name, TimeUtil.format(b.start), TimeUtil.format(b.end)));
                }
            }
        }
    }

    private Meal pendingMeal(DayState ds) {
        for (Meal m : Meal.values()) {
            if (!ds.mealsDone.contains(m) && ds.cur < m.giveUp) return m;
        }
        return null;
    }

    private FixedBlock nextBlock(DayContext ctx) {
        for (FixedBlock b : ctx.blocks) {
            if (!b.done) return b;
        }
        return null;
    }

    private int travel(DayContext ctx, double lat1, double lng1, double lat2, double lng2) {
        return TravelTimeEstimator.minutes(lat1, lng1, lat2, lng2, ctx.transportation);
    }

    private int travelFromCurrent(DayContext ctx, DayState ds, double lat, double lng) {
        if (ds.locLat == null || ds.locLng == null) return 0;
        return travel(ctx, ds.locLat, ds.locLng, lat, lng);
    }

    private int travelToBlock(DayContext ctx, DayState ds, FixedBlock b) {
        return b.hasLocation() ? travelFromCurrent(ctx, ds, b.lat, b.lng) : 0;
    }

    private boolean enterFixedBlockIfDue(DayContext ctx, DayState ds, List<String> warnings) {
        FixedBlock b = nextBlock(ctx);
        if (b == null) return false;
        int travel = travelToBlock(ctx, ds, b);
        if (ds.cur + travel < b.start - 10) return false;

        if (ds.cur + travel > b.start) {
            warnings.add(String.format("Day %d 고정 일정 '%s'에 제시간(%s)에 도착하기 어렵습니다(예상 도착 %s). 입국 시간이나 앞 일정을 확인해 주세요.",
                    ctx.dayNumber, b.name, TimeUtil.format(b.start), TimeUtil.format(ds.cur + travel)));
        }
        ds.items.add(new SimulatedItinerary(SimulatedItinerary.Type.FIXED, null, b.name, b.start, b.end, travel,
                b.hasLocation() ? b.lat : ds.locLat, b.hasLocation() ? b.lng : ds.locLng));
        b.done = true;
        ds.cur = Math.max(ds.cur, b.end);
        ds.bufferPending = 0;
        ds.lastKind = null;
        if (b.hasLocation()) {
            ds.locLat = b.lat;
            ds.locLng = b.lng;
            // 고정 일정 장소가 그날 중심 권역에서 멀면, 이후 일정은 다시 그 권역으로 끌려가지 않게 한다.
            if (ds.seed != null && DistanceUtil.calculateDistance(b.lat, b.lng, ds.seed.lat, ds.seed.lng) > 12.0) {
                ds.leftSeedArea = true;
            }
        }
        for (Meal m : Meal.values()) {
            boolean coversWindow = b.start <= m.mustFrom + 30 && b.end >= m.giveUp - 30;
            boolean overlaps = b.start < m.giveUp && b.end > m.earliest;
            if (coversWindow || (overlaps && looksLikeMeal(b.name))) {
                if (!ds.mealsDone.contains(m)) ds.lastMealEnd = Math.max(ds.lastMealEnd, b.end);
                ds.mealsDone.add(m);
            }
        }
        return true;
    }

    private static boolean looksLikeMeal(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        for (String k : new String[]{"식사", "점심", "저녁", "런치", "디너", "맛집", "레스토랑", "오마카세", "야키니쿠", "스시", "가이세키", "lunch", "dinner"}) {
            if (n.contains(k)) return true;
        }
        return false;
    }

    private void jumpToBlock(DayContext ctx, DayState ds, FixedBlock b) {
        int depart = b.start - travelToBlock(ctx, ds, b);
        if (depart > ds.cur) {
            addFree(ds, ds.cur, depart);
            ds.cur = depart;
        }
        // 다음 루프의 enterFixedBlockIfDue 가 반드시 잡도록 보정
        if (ds.cur + travelToBlock(ctx, ds, b) < b.start - 10) ds.cur = b.start - travelToBlock(ctx, ds, b);
    }

    private void addFree(DayState ds, int from, int to) {
        if (to - from < 30) return;
        ds.items.add(new SimulatedItinerary(SimulatedItinerary.Type.FREE, null, "자유 시간 (주변 산책·휴식)",
                from, to, 0, ds.locLat, ds.locLng));
        ds.bufferPending = 0;
        ds.lastKind = null;
    }

    private boolean addMealPlaceholder(DayContext ctx, DayState ds, Meal meal, FixedBlock nextBlock) {
        int start = ds.cur;
        int end = start + MEAL_PLACEHOLDER_MIN;
        if (end > ctx.endMin) return false;
        if (nextBlock != null && end + travelToBlock(ctx, ds, nextBlock) > nextBlock.start) return false;
        if (ctx.hardDeadlineMin != null && ctx.endAnchor != null) {
            int back = travelFromCurrent(ctx, ds, ctx.endAnchor.getLatitude(), ctx.endAnchor.getLongitude());
            if (end + back > ctx.hardDeadlineMin) return false;
        }
        ds.items.add(new SimulatedItinerary(SimulatedItinerary.Type.MEAL, null, meal.label + " 식사 (주변 자유 식사)",
                start, end, 0, ds.locLat, ds.locLng));
        ds.mealsDone.add(meal);
        ds.lastMealEnd = end;
        ds.cur = end;
        ds.bufferPending = 0;
        ds.lastKind = PlaceKind.RESTAURANT;
        return true;
    }

    /** 현재 위치에서 걸어갈 만한 거리(약 1.2km)에 식당이 하나라도 있는가 */
    private boolean hasRestaurantNearby(DayState ds, TripState st) {
        if (ds.locLat == null || ds.locLng == null) return true;
        for (Cand c : st.pool) {
            if (c.kind != PlaceKind.RESTAURANT && c.kind != PlaceKind.BAR) continue;
            if (Math.abs(c.lat - ds.locLat) > 0.02 || Math.abs(c.lng - ds.locLng) > 0.02) continue;
            if (DistanceUtil.calculateDistance(ds.locLat, ds.locLng, c.lat, c.lng) <= 1.2) return true;
        }
        return false;
    }

    /**
     * 저녁 시간인데 주변에 식당이 없는 외진 곳이면, 그 자리에 "자유 식사"를 넣지 않고
     * 숙소로 돌아가 숙소 근처에서 저녁을 먹는다. 그 뒤로는 숙소 주변만 본다.
     */
    private void dinnerNearLodging(DayContext ctx, DayState ds) {
        Place lodging = ctx.endAnchor;
        int back = travelFromCurrent(ctx, ds, lodging.getLatitude(), lodging.getLongitude());
        int start = ds.cur - ds.bufferPending + back;
        int end = start + MEAL_PLACEHOLDER_MIN;
        ds.items.add(new SimulatedItinerary(SimulatedItinerary.Type.MEAL, null, "저녁 식사 (숙소 근처)",
                start, end, back, lodging.getLatitude(), lodging.getLongitude()));
        ds.mealsDone.add(Meal.DINNER);
        ds.lastMealEnd = end;
        ds.cur = end;
        ds.bufferPending = 0;
        ds.lastKind = PlaceKind.RESTAURANT;
        ds.locLat = lodging.getLatitude();
        ds.locLng = lodging.getLongitude();
        ds.leftSeedArea = true;      // 그날의 중심에서 벗어났으므로 이후 후보는 숙소 기준으로만 평가한다
    }

    private Eval pickBest(DayContext ctx, DayState ds, TripState st, boolean deferred) {
        List<Eval> feasible = new ArrayList<>();
        int minTravel = Integer.MAX_VALUE;
        for (Cand c : st.pool) {
            if (st.visited.contains(c.place.getPlaceId())) continue;
            Eval e = evaluate(c, ctx, ds, st, deferred);
            if (e == null) continue;
            feasible.add(e);
            minTravel = Math.min(minTravel, e.travel);
        }

        Eval best = null;
        for (Eval e : feasible) {
            // "갈 수는 있지만 갈 가치가 없는" 후보 제외: 어차피 필요한 최소 이동분을 빼고도 효용이 크게 음수인 곳
            // (예: 저녁 9시에 다른 도시의 평범한 장소로 1시간 넘게 이동). 식사는 예외.
            // (대기 감점은 "지금 갈지 나중에 갈지"의 문제라 여기서는 빼고 본다)
            if (e.fills == null && e.utility + e.waitPenalty + TRAVEL_WEIGHT * minTravel < MIN_NET_UTILITY) continue;
            if (best == null) {
                best = e;
            } else if (deferred) {
                // 기다려야 하는 후보끼리는 "가장 빨리 시작할 수 있는 곳" 우선
                if (e.start < best.start || (e.start == best.start && e.utility > best.utility)) best = e;
            } else if (e.utility > best.utility) {
                best = e;
            }
        }
        return best;
    }

    /** 해당 날짜의 영업 구간. 정보가 없으면 유형별 기본값, 야경 요청 시 야경 명소는 해 진 뒤로 제한. */
    private List<int[]> intervals(Cand c, LocalDate date, TripState st) {
        return c.intervalCache.computeIfAbsent(date, d -> {
            List<int[]> iv = OpeningHours.intervalsOn(c.place, d);
            if (iv == null) iv = defaultIntervals(c, d);

            // 야외 자연 명소는 "24시간 영업"이라도 해가 있을 때만 의미가 있다 (저녁 6시에 차밭·호수 전망대 방지)
            if (isDaylightOnly(c)) {
                int sunset = nightViewFrom(d);
                List<int[]> daylight = new ArrayList<>();
                for (int[] range : iv) {
                    boolean openAllDay = range[1] - range[0] >= 20 * 60;
                    int end = openAllDay ? Math.min(range[1], sunset) : range[1];
                    if (end > range[0]) daylight.add(new int[]{range[0], end});
                }
                iv = daylight;
            }

            if (st.nightViewLover && c.kind == PlaceKind.ATTRACTION && c.themes.contains("야경")) {
                int from = nightViewFrom(d);
                List<int[]> night = new ArrayList<>();
                for (int[] range : iv) {
                    if (range[1] - Math.max(range[0], from) >= c.dwell) night.add(new int[]{Math.max(range[0], from), range[1]});
                }
                if (!night.isEmpty()) return night;   // 밤에 열지 않는 곳이면 제한하지 않는다
            }
            return iv;
        });
    }

    private static boolean isDaylightOnly(Cand c) {
        return c.kind == PlaceKind.ATTRACTION && !"실내".equals(c.place.getPlaceType())
                && c.themes.contains("자연") && !c.themes.contains("야경") && !c.themes.contains("온천");
    }

    private static List<int[]> defaultIntervals(Cand c, LocalDate date) {
        List<int[]> iv = new ArrayList<>();
        switch (c.kind) {
            case RESTAURANT: iv.add(new int[]{11 * 60, 22 * 60}); break;
            case CAFE: iv.add(new int[]{9 * 60, 20 * 60}); break;
            case BAR: iv.add(new int[]{17 * 60, 24 * 60}); break;
            case SHOPPING: iv.add(new int[]{10 * 60, 20 * 60}); break;
            case THEME_PARK: iv.add(new int[]{9 * 60, 20 * 60}); break;
            default:
                // 구글에 영업시간이 없는 관광지는 대부분 거리·공원 같은 개방 공간이다.
                if ("실내".equals(c.place.getPlaceType())) {
                    iv.add(new int[]{9 * 60, 18 * 60});
                } else if (c.themes.contains("자연") && !c.themes.contains("야경")) {
                    iv.add(new int[]{7 * 60, nightViewFrom(date)});   // 공원·산책로는 해 지기 전까지만
                } else {
                    iv.add(new int[]{7 * 60, 22 * 60});
                }
        }
        return iv;
    }

    /** 야경을 보기 시작할 만한 시각 (월별 일몰 근사) */
    private static int nightViewFrom(LocalDate date) {
        int month = date.getMonthValue();
        if (month == 11 || month == 12 || month == 1) return 17 * 60;
        if (month == 2 || month == 3 || month == 10) return 17 * 60 + 30;
        if (month == 4 || month == 9) return 18 * 60;
        return 18 * 60 + 30;
    }

    /**
     * 후보 하나가 "지금" 방문 가능한지 검사하고 효용을 계산한다. 불가능하면 null.
     * 여기 있는 조건이 곧 일정의 불변식이다(영업시간, 고정 일정, 일과 종료, 출국 마감).
     */
    private Eval evaluate(Cand c, DayContext ctx, DayState ds, TripState st, boolean deferred) {
        // ---- 하루 한도 ----
        switch (c.kind) {
            case THEME_PARK:
                if (ds.themeParkDone) return null;
                // 공항에서 짐을 든 채 바로 테마파크로 가는 일정은 만들지 않는다 (당일치기는 예외)
                if (isAirport(ctx.startAnchor) && isLodging(ctx.endAnchor)) return null;
                break;
            case SHOPPING:
                if (ds.shopping >= (st.shoppingLover ? 3 : 1)) return null;
                break;
            case CAFE:
                if (ds.cafes >= (st.cafeLover ? 2 : 1) || ds.lastKind == PlaceKind.CAFE) return null;
                if (ds.cur - ds.lastCafeEnd < 150) return null;   // 카페 테마라도 연달아 가지 않는다
                break;
            case BAR:
                if (ds.bars >= 1) return null;
                break;
            default:
                break;
        }
        // 외딴 장소는 그날의 중심이 바로 그곳(또는 그 5km 안)일 때만 간다
        if (c.remoteLone && ds.seed != c) {
            if (ds.seed == null || ds.leftSeedArea
                    || DistanceUtil.calculateDistance(c.lat, c.lng, ds.seed.lat, ds.seed.lng) > 5.0) return null;
        }
        // 같은 체인의 다른 지점은 여행 중 한 번만 (돈키호테 4개 지점 같은 일정 방지)
        if (c.brandKey != null && st.visitedBrands.contains(c.brandKey)) return null;

        int travel = travelFromCurrent(ctx, ds, c.lat, c.lng);
        int arrive = ds.cur + travel;

        // ---- 영업시간 ----
        int dwell = c.dwell;
        if (c.kind == PlaceKind.RESTAURANT || c.kind == PlaceKind.BAR) {
            Meal next = pendingMeal(ds);
            if (next != null && next.optional && arrive >= earliest(ds, next) - 15) {
                dwell = Math.min(dwell, next == Meal.SNACK ? SNACK_DWELL_MAX_MIN : LATE_DWELL_MAX_MIN);
            }
        }
        int start = -1;
        for (int[] iv : intervals(c, ctx.date, st)) {
            int s = Math.max(arrive, iv[0]);
            if (c.kind == PlaceKind.THEME_PARK) {
                int latestEnd = Math.min(iv[1], ctx.endMin);
                if (latestEnd - s >= 300) {
                    start = s;
                    dwell = Math.min(c.dwell, latestEnd - s);
                    break;
                }
            } else if (s + dwell <= iv[1]) {
                start = s;
                break;
            }
        }
        if (start < 0) return null;

        int wait = start - arrive;
        boolean firstFromLodging = ds.items.isEmpty() && !ctx.arrivalFixed && !isAirport(ctx.startAnchor);
        // 첫 방문지가 조금 늦게 열면 숙소에서 그만큼 늦게 나오면 되므로 대기로 치지 않는다.
        // (60분까지만: 그보다 길면 오전을 통째로 버리게 되므로 일반 대기 규칙을 따른다)
        boolean waitIsFree = firstFromLodging && wait <= 60;
        if (!waitIsFree) {
            if (!deferred && wait > MAX_WAIT_MIN) return null;
            if (deferred && (wait <= MAX_WAIT_MIN || wait > MAX_DEFERRED_WAIT_MIN)) return null;
        } else if (deferred) {
            return null;
        }

        int end = start + dwell;
        int finish = end + st.buffer;

        // ---- 일과 종료 ----
        if (end > ctx.endMin) return null;

        // ---- 다음 고정 일정에 늦지 않는가 ----
        FixedBlock nb = nextBlock(ctx);
        if (nb != null) {
            int toBlock = nb.hasLocation() ? travel(ctx, c.lat, c.lng, nb.lat, nb.lng) : 0;
            if (finish + toBlock > nb.start) return null;
        }

        // ---- 방문 뒤 도착 앵커로 돌아갈 수 있는가 (출국 시간 위반 방지의 핵심) ----
        if (ctx.endAnchor != null) {
            int back = travel(ctx, c.lat, c.lng, ctx.endAnchor.getLatitude(), ctx.endAnchor.getLongitude());
            if (ctx.hardDeadlineMin != null) {
                if (finish + back > ctx.hardDeadlineMin) return null;
            } else if (end + back > ctx.endMin + RETURN_GRACE_MIN) {
                return null;
            }
        }

        // ---- 식사 규칙 ----
        Meal pending = pendingMeal(ds);
        Meal fills = null;
        boolean longStay = dwell >= 240;   // 테마파크 등: 안에서 식사한다고 본다
        boolean mealPlace = c.kind == PlaceKind.RESTAURANT || c.kind == PlaceKind.BAR;
        // 아침은 일찍 여는 카페·베이커리(모닝 세트)로도 해결한다
        boolean breakfastCafe = c.kind == PlaceKind.CAFE && !ds.mealsDone.contains(Meal.BREAKFAST)
                && start >= earliest(ds, Meal.BREAKFAST) && start < Meal.BREAKFAST.giveUp;
        if (mealPlace || breakfastCafe) {
            if (c.kind == PlaceKind.BAR && start < 17 * 60 + 30) return null;
            if (breakfastCafe) fills = Meal.BREAKFAST;
            for (Meal m : Meal.values()) {
                if (breakfastCafe) break;
                boolean inWindow = start >= earliest(ds, m) && start < m.giveUp;
                // 이자카야는 저녁·야식으로만 센다
                if (!ds.mealsDone.contains(m) && inWindow && (c.kind != PlaceKind.BAR || m == Meal.DINNER || m == Meal.LATE)) fills = m;
            }
            if (fills == null) {
                // 식사 시간대가 아니면 식당은 넣지 않는다. 예외: 맛집 테마일 때 저녁 뒤 이자카야 한 곳(2차)
                // (야식을 따로 요청했으면 야식이 그 역할을 하므로 2차는 넣지 않는다)
                boolean secondRound = c.kind == PlaceKind.BAR && st.foodLover && ds.bars == 0 && !st.meals.contains(Meal.LATE)
                        && ds.mealsDone.contains(Meal.DINNER) && start >= 20 * 60;
                if (!secondRound) return null;
            } else if (pending != null && ds.cur >= mustFrom(ds, pending)
                    && travel > (ds.items.isEmpty() && pending != Meal.BREAKFAST ? 90 : MUST_EAT_MAX_TRAVEL_MIN)) {
                // 식사 시간에 30분 넘게 이동해야 하는 식당은 제외 (공항·숙소에서 막 출발하는 경우는 예외)
                return null;
            } else if (!deferred && ds.cur < mustFrom(ds, fills) && wait > 15) {
                // 아직 식사 시간 전인데 식당 문 열기를 기다리느니 그 사이에 다른 곳을 본다
                return null;
            }
        } else if (pending != null) {
            if (ds.cur >= mustFrom(ds, pending)) return null;                   // 식사가 먼저 (테마파크도 식사 뒤에 간다)
            if (!longStay && finish > pending.latestStart) return null;         // 이 방문 때문에 식사 시간을 놓치면 안 됨
        }

        // ---- 효용 ----
        Eval e = new Eval();
        e.cand = c;
        e.travel = travel;
        e.start = start;
        e.end = end;
        e.wait = wait;
        e.dwell = dwell;
        e.fills = fills;

        double u = c.baseScore + themeBalanceBonus(c, st, ds);
        u -= TRAVEL_WEIGHT * travel;
        e.waitPenalty = (waitIsFree ? 0.5 : WAIT_WEIGHT) * wait;
        u -= e.waitPenalty;
        if (ds.seed != null && !ds.leftSeedArea) {
            if (ds.seed == c) {
                u += SEED_BONUS;
            } else {
                double km = DistanceUtil.calculateDistance(c.lat, c.lng, ds.seed.lat, ds.seed.lng);
                u -= Math.min(180.0, SEED_DISTANCE_WEIGHT * km);
            }
        }
        if (!ds.leftSeedArea && ctx.city != null && c.place.getCity() != null && !ctx.city.equals(c.place.getCity())) {
            u -= CITY_MISMATCH_PENALTY;
        }
        if (ctx.badWeather) {
            String type = c.place.getPlaceType();
            if ("실내".equals(type)) u += 25.0;
            else if ("실외".equals(type)) u -= 35.0;
            else if (c.themes.contains("자연")) u -= 25.0;
        }
        if (fills != null) u += 20.0;
        if (!st.nightViewLover && c.themes.contains("야경") && c.kind == PlaceKind.ATTRACTION) {
            if (start >= nightViewFrom(ctx.date)) u += 25.0;
        }
        e.utility = u;
        return e;
    }

    /** 덜 채워진 요청 테마일수록 가산점 → 테마가 한쪽으로 쏠리지 않게 한다. */
    private double themeBalanceBonus(Cand c, TripState st, DayState ds) {
        if (c.matched.isEmpty() || st.themes.size() < 2) {
            return 0.0;
        }
        int max = st.themeCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        double best = 0.0;
        for (String t : c.matched) {
            double bonus = 12.0 * (max - st.themeCounts.getOrDefault(t, 0));
            if (ds.dayThemeCounts.getOrDefault(t, 0) == 0) bonus += 10.0;
            best = Math.max(best, Math.min(60.0, bonus));
        }
        return best;
    }

    private void commitVisit(DayContext ctx, DayState ds, TripState st, Eval e) {
        Cand c = e.cand;
        int departFromPrev = e.start - e.travel;

        if (ds.items.isEmpty() && departFromPrev > ds.cur && !ctx.arrivalFixed && !isAirport(ctx.startAnchor)) {
            ds.startDeparture = departFromPrev;                 // 첫 방문지가 늦게 열면 숙소에서 그만큼 늦게 출발
        } else if (departFromPrev - ds.cur >= 30) {
            addFree(ds, ds.cur, departFromPrev);                // 기다리는 시간은 자유 시간으로 표시
        }

        ds.items.add(new SimulatedItinerary(SimulatedItinerary.Type.VISIT, c.place, null, e.start, e.end, e.travel, c.lat, c.lng));
        ds.cur = e.end + st.buffer;
        ds.bufferPending = st.buffer;
        ds.locLat = c.lat;
        ds.locLng = c.lng;
        ds.lastKind = c.kind;
        ds.visitCount++;

        switch (c.kind) {
            case CAFE: ds.cafes++; ds.lastCafeEnd = e.end; break;
            case BAR: ds.bars++; break;
            case SHOPPING: ds.shopping++; break;
            case THEME_PARK: ds.themeParkDone = true; break;
            default: break;
        }
        if (e.fills != null) {
            ds.mealsDone.add(e.fills);
            ds.lastMealEnd = e.end;
        }
        if (e.dwell >= 240) {
            for (Meal m : Meal.values()) {
                if (e.start <= m.mustFrom + 30 && e.end >= m.latestStart) ds.mealsDone.add(m);
            }
        }

        st.visited.add(c.place.getPlaceId());
        if (c.brandKey != null) st.visitedBrands.add(c.brandKey);
        for (String t : c.matched) {
            st.themeCounts.merge(t, 1, Integer::sum);
            ds.dayThemeCounts.merge(t, 1, Integer::sum);
        }
    }

    // ---- 그날의 중심(시드) 선택: 일차별 권역 분리 + 테마 보장 ----

    private boolean roughlyFeasible(Cand c, DayContext ctx, TripState st) {
        int travel = ctx.startAnchor == null ? 0
                : travel(ctx, ctx.startAnchor.getLatitude(), ctx.startAnchor.getLongitude(), c.lat, c.lng);
        int arrive = ctx.startMin + travel;
        for (int[] iv : intervals(c, ctx.date, st)) {
            int s = Math.max(arrive, iv[0]);
            int dwell = c.kind == PlaceKind.THEME_PARK ? 300 : c.dwell;
            int end = s + dwell;
            if (end > iv[1] || end > ctx.endMin) continue;
            if (ctx.endAnchor != null && ctx.hardDeadlineMin != null) {
                int back = travel(ctx, c.lat, c.lng, ctx.endAnchor.getLatitude(), ctx.endAnchor.getLongitude());
                if (end + st.buffer + back > ctx.hardDeadlineMin) continue;
            }
            return true;
        }
        return false;
    }

    /**
     * 그날 동선의 중심이 될 장소를 고른다.
     *  - 지금까지 가장 덜 채워진 요청 테마의 장소를 우선한다(테마 누락 방지).
     *  - 주변에 점수 높은 장소가 많이 모인 곳일수록 유리하다(하루를 한 권역에서 보내도록).
     * 시드는 큰 가산점을 받아 그날 일정에 들어가고, 나머지 후보는 시드와 가까울수록 유리해진다.
     */
    private Cand chooseSeed(DayContext ctx, TripState st) {
        List<String> order = new ArrayList<>();
        for (String t : st.themes) {
            if (!"맛집".equals(t) && !"카페".equals(t)) order.add(t);   // 식당·카페는 어디에나 있어 권역 중심으로 부적합
        }
        order.sort(Comparator.comparingInt(t -> st.themeCounts.getOrDefault(t, 0)));
        order.add(null);   // 마지막 대안: 테마 무관

        Cand fallback = null;
        double fallbackScore = Double.NEGATIVE_INFINITY;
        boolean remoteAllowed = st.remoteDaysUsed < st.maxRemoteDays && ctx.hardDeadlineMin == null
                && isLodging(ctx.startAnchor) && isLodging(ctx.endAnchor);
        for (String theme : order) {
            Cand best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            // 1차: 도심·근교의 자격 있는 곳. 2차: 그 테마를 시내에서 더 채울 수 없을 때만 외딴 곳 하나를 허용.
            for (int pass = 0; pass < 2 && best == null; pass++) {
                if (pass == 1 && (theme == null || !remoteAllowed)) break;
                final boolean remotePass = pass == 1;
                List<Cand> options = st.pool.stream()
                        .filter(c -> !st.visited.contains(c.place.getPlaceId()))
                        .filter(c -> theme == null ? !c.kind.isFood() : c.matched.contains(theme))
                        .filter(c -> remotePass ? !seedEligible(c, st) : seedEligible(c, st))
                        .filter(c -> roughlyFeasible(c, ctx, st))
                        .sorted(Comparator.comparingDouble((Cand c) -> -c.baseScore))
                        .limit(40)
                        .collect(Collectors.toList());
                for (Cand c : options) {
                    double sc = remotePass ? c.baseScore - 2.0 * c.distFromCenter : seedScore(c, ctx, st);
                    if (sc > bestScore) {
                        bestScore = sc;
                        best = c;
                    }
                }
            }
            if (best == null) continue;
            boolean inCity = ctx.city == null || ctx.city.equals(best.place.getCity());
            if (inCity) return best;
            if (bestScore > fallbackScore) {
                fallbackScore = bestScore;
                fallback = best;
            }
        }
        return fallback;
    }

    /**
     * 그날의 중심이 될 자격.
     * 도심에서 가까운 곳은 그대로 허용하고, 먼 곳은 주변 5km 안에 함께 볼 곳이 3곳 이상 있을 때만 허용한다.
     * (덜 채워진 테마를 찾아 점점 멀리 나가다가, 온천 한 곳 때문에 하루를 통째로 쓰는 일을 막는다)
     */
    private boolean seedEligible(Cand c, TripState st) {
        if (c.distFromCenter <= NEAR_SEED_KM) return true;
        int companions = 0;
        for (Cand o : st.pool) {
            if (o == c || o.kind.isFood() || st.visited.contains(o.place.getPlaceId())) continue;
            if (Math.abs(o.lat - c.lat) > 0.06 || Math.abs(o.lng - c.lng) > 0.07) continue;
            if (DistanceUtil.calculateDistance(c.lat, c.lng, o.lat, o.lng) <= 5.0 && ++companions >= 3) return true;
        }
        return false;
    }

    private double seedScore(Cand c, DayContext ctx, TripState st) {
        double neighborhood = 0.0;
        for (Cand o : st.pool) {
            if (o == c || o.kind.isFood() || st.visited.contains(o.place.getPlaceId())) continue;
            if (Math.abs(o.lat - c.lat) > 0.03 || Math.abs(o.lng - c.lng) > 0.04) continue;   // 빠른 사전 컷(약 3km)
            if (DistanceUtil.calculateDistance(c.lat, c.lng, o.lat, o.lng) <= 2.5) {
                neighborhood += Math.min(150.0, Math.max(0.0, o.baseScore));
            }
        }
        double score = c.baseScore + 0.3 * Math.min(600.0, neighborhood);
        if (ctx.startAnchor != null) {
            score -= 0.5 * travel(ctx, ctx.startAnchor.getLatitude(), ctx.startAnchor.getLongitude(), c.lat, c.lng);
        }
        if (ctx.endAnchor != null) {
            score -= 0.5 * travel(ctx, c.lat, c.lng, ctx.endAnchor.getLatitude(), ctx.endAnchor.getLongitude());
        }
        if (ctx.city != null && c.place.getCity() != null && !ctx.city.equals(c.place.getCity())) score -= 200.0;
        if (ctx.badWeather && "실외".equals(c.place.getPlaceType())) score -= 40.0;
        return score;
    }

    // ---- 하루 마무리: 출발/도착 앵커 붙이기 ----

    private void finishDay(DayContext ctx, DayState ds, DayPlan dayPlan, List<String> warnings) {
        Place start = ctx.startAnchor;
        Place end = ctx.endAnchor;
        boolean hasActivity = !ds.items.isEmpty();

        int backFromCurrent = end == null ? 0 : travelFromCurrent(ctx, ds, end.getLatitude(), end.getLongitude());
        int limit = (ctx.hardDeadlineMin != null && end != null) ? Math.min(ctx.endMin, ctx.hardDeadlineMin - backFromCurrent) : ctx.endMin;

        if (!hasActivity) {
            if (ctx.hardDeadlineMin != null && end != null) {
                // 방문 없이 공항으로만 가는 날: 수속 마감에 맞춰 도착하도록 출발 시각을 역산한다.
                int depart = ctx.hardDeadlineMin - backFromCurrent;
                if (depart < ctx.startMin) {
                    if (ctx.arrivalFixed) {
                        warnings.add(String.format("Day %d: 입국 후 출국 수속 마감(%s)까지 공항에 도착할 수 없습니다. 입·출국 시간을 확인해 주세요.",
                                ctx.dayNumber, TimeUtil.format(ctx.hardDeadlineMin)));
                        depart = ctx.startMin;   // 착륙보다 일찍 출발할 수는 없다
                    }
                    // 그 외(이른 출국편)는 선호 시작 시각 전이라도 숙소를 일찍 나선다.
                    ds.startDeparture = depart;
                } else if (depart - ctx.startMin >= 90) {
                    addFree(ds, ctx.startMin, depart);
                    ds.startDeparture = ctx.startMin;
                } else {
                    ds.startDeparture = depart;
                }
                ds.cur = depart;
            } else if (limit - ctx.startMin >= 180) {
                // 시간은 있는데 넣을 후보가 하나도 없는 날
                int to = Math.min(limit, ctx.startMin + 180);
                addFree(ds, ctx.startMin, to);
                ds.cur = to;
                warnings.add(String.format("Day %d: 조건에 맞는 장소 후보가 없어 자유 시간으로 채웠습니다. 해당 도시의 장소 데이터를 더 수집해 주세요.", ctx.dayNumber));
            }
        } else if (limit - ds.cur >= 150 && (ctx.hardDeadlineMin != null || ds.cur < 17 * 60)) {
            // 후보가 바닥나 낮에 일정이 끝난 경우: 조용히 끝내지 않고 표시한다. (저녁 식사 뒤 일찍 마치는 것은 정상)
            int to = Math.min(limit, ds.cur + 180);
            warnings.add(String.format("Day %d: 넣을 수 있는 장소가 부족해 %s 이후는 자유 시간으로 두었습니다.", ctx.dayNumber, TimeUtil.format(ds.cur)));
            addFree(ds, ds.cur, to);
            ds.cur = to;
        }

        if (start != null) {
            dayPlan.items.add(new SimulatedItinerary(SimulatedItinerary.Type.START, start, null, ds.startDeparture, null, 0,
                    start.getLatitude(), start.getLongitude()));
        }
        dayPlan.items.addAll(ds.items);

        // 숙소에서 나가지 않은 날은 "숙소 출발 → 숙소 도착" 두 줄 대신 한 줄만 남긴다.
        boolean sameAnchorNoActivity = ds.items.isEmpty() && isLodging(start) && end != null
                && start.getPlaceId() != null && start.getPlaceId().equals(end.getPlaceId());
        if (end != null && !sameAnchorNoActivity) {
            int back = travelFromCurrent(ctx, ds, end.getLatitude(), end.getLongitude());
            // 공항으로 갈 때는 여유 시간을 그대로 두고, 숙소로 돌아갈 때는 붙이지 않는다.
            int arrival = ds.cur + back - (isLodging(end) ? ds.bufferPending : 0);
            int totalTravel = back;
            for (SimulatedItinerary item : ds.items) totalTravel += item.getTravelMinutes();
            if (totalTravel >= LONG_TRAVEL_DAY_MIN) {
                warnings.add(String.format("Day %d: 이동에만 약 %d분이 듭니다. 숙소와 방문지, 또는 방문지 사이가 멉니다.", ctx.dayNumber, totalTravel));
            }
            if (ctx.hardDeadlineMin != null && arrival > ctx.hardDeadlineMin && !(ds.items.isEmpty() && ctx.arrivalFixed)) {
                warnings.add(String.format("Day %d: 공항 도착 예상 %s 이 출국 수속 마감 %s 보다 늦습니다. 고정 일정이나 출국 시간을 확인해 주세요.",
                        ctx.dayNumber, TimeUtil.format(arrival), TimeUtil.format(ctx.hardDeadlineMin)));
            }
            dayPlan.items.add(new SimulatedItinerary(SimulatedItinerary.Type.END, end, null, arrival, null, back,
                    end.getLatitude(), end.getLongitude()));
        }
    }

    // =====================================================================================
    // 순서가 정해진 하루 일정 검증 (reroute 용)
    // =====================================================================================

    /**
     * 사용자가 정한 순서 그대로 시간을 흘려 보며 검증한다.
     * 영업시간·일과 종료·고정 일정·출국 마감 중 하나라도 어기면 success=false 와 문제 장소를 돌려준다.
     * (이전 시뮬레이터는 항상 success=true 라서 재탐색 경고가 절대 뜨지 않았다.)
     */
    public SimulationResult simulateFixedOrder(DayContext ctx, List<Place> ordered, PlanRequest request) {
        SimulationResult result = new SimulationResult();
        List<SimulatedItinerary> route = new ArrayList<>();
        int buffer = calculateBufferTime(request);

        DayState ds = new DayState();
        ds.cur = ctx.startMin;
        if (ctx.startAnchor != null) {
            ds.locLat = ctx.startAnchor.getLatitude();
            ds.locLng = ctx.startAnchor.getLongitude();
        }
        List<String> ignoredWarnings = new ArrayList<>();
        dropImpossibleBlocks(ctx, ignoredWarnings);
        ds.cur = ctx.startMin;
        Integer firstDeparture = null;

        for (Place p : ordered) {
            if (p.getLatitude() == null || p.getLongitude() == null) continue;
            int dwell = calculateDwellTime(p, request);

            int start;
            int travel;
            while (true) {
                travel = travelFromCurrent(ctx, ds, p.getLatitude(), p.getLongitude());
                int arrive = ds.cur + travel;
                List<int[]> iv = OpeningHours.intervalsOn(p, ctx.date);
                start = -1;
                if (iv == null) {
                    start = arrive;   // 영업시간 정보가 없으면 시간 제약 없이 통과
                } else {
                    for (int[] range : iv) {
                        int s = Math.max(arrive, range[0]);
                        if (s + dwell <= range[1]) {
                            start = s;
                            break;
                        }
                    }
                }
                // 이 방문이 다음 고정 일정과 겹치면 고정 일정을 먼저 소화하고 다시 계산
                FixedBlock nb = nextBlock(ctx);
                if (nb != null && (start < 0 || start + dwell + buffer > nb.start)) {
                    int toBlock = travelToBlock(ctx, ds, nb);
                    route.add(new SimulatedItinerary(SimulatedItinerary.Type.FIXED, null, nb.name, nb.start, nb.end, toBlock,
                            nb.hasLocation() ? nb.lat : ds.locLat, nb.hasLocation() ? nb.lng : ds.locLng));
                    nb.done = true;
                    ds.cur = Math.max(ds.cur, nb.end);
                    if (nb.hasLocation()) {
                        ds.locLat = nb.lat;
                        ds.locLng = nb.lng;
                    }
                    continue;
                }
                break;
            }

            if (start < 0) {
                return fail(result, p, "[" + p.getName() + "] 도착 예상 " + TimeUtil.format(ds.cur + travel) + " 기준으로 영업시간 안에 방문을 마칠 수 없습니다.");
            }
            int end = start + dwell;
            if (end > ctx.endMin) {
                return fail(result, p, "[" + p.getName() + "] 방문이 " + TimeUtil.format(end) + " 에 끝나 일과 종료 시각(" + TimeUtil.format(ctx.endMin) + ")을 넘깁니다.");
            }
            if (ctx.endAnchor != null && ctx.hardDeadlineMin != null) {
                int back = travel(ctx, p.getLatitude(), p.getLongitude(), ctx.endAnchor.getLatitude(), ctx.endAnchor.getLongitude());
                if (end + buffer + back > ctx.hardDeadlineMin) {
                    return fail(result, p, "[" + p.getName() + "] 방문 후 공항 도착이 " + TimeUtil.format(end + buffer + back)
                            + " 로, 출국 수속 마감(" + TimeUtil.format(ctx.hardDeadlineMin) + ")을 넘깁니다.");
                }
            }

            if (firstDeparture == null) firstDeparture = start - travel;
            route.add(new SimulatedItinerary(SimulatedItinerary.Type.VISIT, p, null, start, end, travel, p.getLatitude(), p.getLongitude()));
            ds.cur = end + buffer;
            ds.locLat = p.getLatitude();
            ds.locLng = p.getLongitude();
        }

        // 남은 고정 일정
        for (FixedBlock b : ctx.blocks) {
            if (b.done) continue;
            int toBlock = travelToBlock(ctx, ds, b);
            route.add(new SimulatedItinerary(SimulatedItinerary.Type.FIXED, null, b.name, b.start, b.end, toBlock,
                    b.hasLocation() ? b.lat : ds.locLat, b.hasLocation() ? b.lng : ds.locLng));
            b.done = true;
            ds.cur = Math.max(ds.cur, b.end);
            if (b.hasLocation()) {
                ds.locLat = b.lat;
                ds.locLng = b.lng;
            }
        }

        List<SimulatedItinerary> full = new ArrayList<>();
        if (ctx.startAnchor != null) {
            boolean canLeaveLate = !ctx.arrivalFixed && !isAirport(ctx.startAnchor);
            int depart = (canLeaveLate && firstDeparture != null && route.get(0).getType() == SimulatedItinerary.Type.VISIT)
                    ? Math.max(ctx.startMin, firstDeparture) : ctx.startMin;
            full.add(new SimulatedItinerary(SimulatedItinerary.Type.START, ctx.startAnchor, null, depart, null, 0,
                    ctx.startAnchor.getLatitude(), ctx.startAnchor.getLongitude()));
        }
        full.addAll(route);
        if (ctx.endAnchor != null) {
            int back = travelFromCurrent(ctx, ds, ctx.endAnchor.getLatitude(), ctx.endAnchor.getLongitude());
            int arrival = ds.cur + back;
            if (ctx.hardDeadlineMin != null && arrival > ctx.hardDeadlineMin && !ordered.isEmpty()) {
                Place last = ordered.get(ordered.size() - 1);
                return fail(result, last, "공항 도착 예상 " + TimeUtil.format(arrival) + " 이 출국 수속 마감(" + TimeUtil.format(ctx.hardDeadlineMin) + ")을 넘깁니다.");
            }
            full.add(new SimulatedItinerary(SimulatedItinerary.Type.END, ctx.endAnchor, null, arrival, null, back,
                    ctx.endAnchor.getLatitude(), ctx.endAnchor.getLongitude()));
        }

        result.setSuccess(true);
        result.setValidRoute(full);
        return result;
    }

    private SimulationResult fail(SimulationResult result, Place problem, String reason) {
        result.setSuccess(false);
        result.setProblemPlace(problem);
        result.setReason(reason);
        return result;
    }
}
