package com.travel.planner.service;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.Place;
import com.travel.planner.service.PlanService.DayPlan;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.util.PlaceDescriber;
import com.travel.planner.util.PlaceKind;
import com.travel.planner.util.ThemeVocabulary;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 완성된 일정에서 "어떤 여행인지"를 글로 정리한다.
 *   - 일자별 개요: 그 날의 성격(도착·이동·출국), 중심 테마, 주요 방문지, 식사, 이동 시간
 *   - 여행 전체 요약: 도시·기간·테마·방문 수·도시 순서·대표 방문지·숙소
 *
 * AI 를 부르지 않고 일정 결과만으로 만든다. 그래서 일정 생성이 느려지지 않고,
 * 일정에 없는 내용을 지어내지 않으며, 같은 일정이면 항상 같은 글이 나온다.
 */
public final class PlanSummarizer {

    private static final int MAX_HIGHLIGHTS_PER_DAY = 3;
    private static final int MAX_HIGHLIGHTS_PER_TRIP = 4;
    private static final int MAX_THEMES_PER_DAY = 3;

    private PlanSummarizer() {}

    // =====================================================================
    // 일자별 개요
    // =====================================================================

    public static List<AiRouteResponse.DaySummary> summarizeDays(List<DayPlan> days) {
        List<AiRouteResponse.DaySummary> out = new ArrayList<>();
        String previousCity = null;
        for (DayPlan day : days) {
            out.add(summarizeDay(day.getDayNumber(), day.getDate(), day.getCity(), day.getItems(), previousCity, day.getBadWeather()));
            if (day.getCity() != null) previousCity = day.getCity();
        }
        return out;
    }

    /**
     * 하루 개요.
     * @param previousCity 전날의 도시 (도시가 바뀌는 날을 "이동하는 날"로 적기 위해. 모르면 null)
     * @param badWeather   비·눈 예보 (모르면 null)
     */
    public static AiRouteResponse.DaySummary summarizeDay(int dayNumber, LocalDate date, String city, List<SimulatedItinerary> items,
                                                          String previousCity, Boolean badWeather) {
        AiRouteResponse.DaySummary summary = new AiRouteResponse.DaySummary();
        summary.setDay(dayNumber);
        if (date != null) {
            summary.setDate(date.toString());
            summary.setDayOfWeek(dayOfWeek(date.getDayOfWeek()));
        }
        summary.setBadWeather(badWeather);

        List<SimulatedItinerary> visits = items.stream()
                .filter(i -> i.getType() == SimulatedItinerary.Type.VISIT && i.getPlace() != null)
                .collect(Collectors.toList());
        List<SimulatedItinerary> sights = visits.stream().filter(i -> !PlaceKind.of(i.getPlace()).isFood()).collect(Collectors.toList());
        long foodVisits = visits.size() - sights.size();
        long freeMeals = items.stream().filter(i -> i.getType() == SimulatedItinerary.Type.MEAL).count();
        long restaurants = visits.stream().filter(i -> PlaceKind.of(i.getPlace()) == PlaceKind.RESTAURANT
                || PlaceKind.of(i.getPlace()) == PlaceKind.BAR).count();   // 카페는 뺀 '끼니' 수

        if (city == null || city.isBlank()) city = dominantCity(visits);
        summary.setCity(city);

        List<String> themes = dayThemes(visits);
        List<String> highlights = highlights(sights, MAX_HIGHLIGHTS_PER_DAY);
        int travel = items.stream().mapToInt(SimulatedItinerary::getTravelMinutes).sum();

        summary.setThemes(themes);
        summary.setHighlights(highlights);
        summary.setVisitCount(visits.size());
        summary.setMealCount((int) (foodVisits + freeMeals));
        summary.setTravelMinutes(travel);
        if (!items.isEmpty()) {
            summary.setStartTime(items.get(0).getTime());
            summary.setEndTime(items.get(items.size() - 1).getTime());
        }

        Place first = items.isEmpty() ? null : items.get(0).getPlace();
        Place last = items.isEmpty() ? null : items.get(items.size() - 1).getPlace();
        boolean arrival = !items.isEmpty() && items.get(0).getType() == SimulatedItinerary.Type.START && PlanService.isAirport(first);
        boolean departure = !items.isEmpty() && items.get(items.size() - 1).getType() == SimulatedItinerary.Type.END && PlanService.isAirport(last);
        boolean moving = !arrival && city != null && previousCity != null && !previousCity.equals(city);

        // ---- 제목 ----
        String cityLabel = city == null ? "" : city;
        String head;
        if (arrival && departure) head = join(" ", cityLabel, "당일 일정");
        else if (arrival) head = join(" ", cityLabel, "도착");
        else if (departure) head = join(" ", cityLabel, "출국일");
        else if (moving) head = previousCity + " → " + cityLabel;
        else head = cityLabel;
        // 제목의 테마는 관광지 쪽 테마를 먼저 쓴다. 식당(카페 제외)이 3곳 이상이고 관광지보다 많은 날(식도락 위주)에만 '맛집'을 함께 적는다.
        List<String> focus = themes.stream().filter(t -> !"맛집".equals(t) && !"카페".equals(t)).limit(2).collect(Collectors.toList());
        String themeLabel;
        if (visits.isEmpty()) themeLabel = "자유 일정";
        else if (focus.isEmpty()) themeLabel = themes.isEmpty() ? "관광" : themes.get(0);
        else if (restaurants >= 3 && restaurants > sights.size()) themeLabel = focus.get(0) + "·맛집";
        else themeLabel = String.join("·", focus);
        summary.setTitle(head.isEmpty() ? themeLabel : head + " · " + themeLabel);

        // ---- 본문 ----
        List<String> sentences = new ArrayList<>();
        String inCity = city == null ? "" : city + "에서 ";
        if (arrival && departure) {
            sentences.add(first.getName() + "에 도착해 " + (city == null ? "" : city + " ") + "일정을 마친 뒤 그날 출국하는 당일 일정입니다.");
        } else if (arrival) {
            sentences.add(first.getName() + "에 도착해 " + (city == null ? "" : city + " ") + "일정을 시작하는 날입니다.");
        } else if (departure) {
            sentences.add(inCity + "마지막 일정을 보낸 뒤 " + last.getName() + "에서 출국하는 날입니다.");
        } else if (moving) {
            sentences.add(previousCity + "에서 " + city + josa(city, "으로", "로") + " 넘어가 보내는 날입니다.");
        } else if (visits.isEmpty()) {
            sentences.add(inCity + "정해진 방문지 없이 자유롭게 보내는 날입니다.");
        } else {
            sentences.add(inCity + "온전히 하루를 보내는 날입니다.");
        }

        if (!sights.isEmpty()) {
            List<String> kinds = distinctSubTypes(sights, 3);
            String kindList = String.join(", ", kinds);
            String around = kindList + josa(kinds.get(kinds.size() - 1), "을", "를") + " 둘러봅니다.";
            sentences.add(focus.isEmpty() ? around : String.join("·", focus) + " 테마를 중심으로 " + around);
            sentences.add("주요 방문지는 " + String.join(", ", highlights) + "입니다.");
        } else if (!visits.isEmpty()) {
            sentences.add("관광지 방문 없이 식사와 휴식 위주로 가볍게 구성했습니다.");
        } else if (arrival || departure || moving) {
            sentences.add("정해진 방문지는 없고 자유 시간으로 두었습니다.");
        }

        List<String> fixed = items.stream().filter(i -> i.getType() == SimulatedItinerary.Type.FIXED)
                .map(i -> i.getDisplayName() + "(" + i.getTime() + "~" + i.getEndTime() + ")").collect(Collectors.toList());
        if (!fixed.isEmpty()) sentences.add("고정 일정 " + String.join(", ", fixed) + "에 맞춰 앞뒤를 채웠습니다.");

        List<String> meals = mealLabels(items);
        if (!meals.isEmpty()) sentences.add("식사는 " + String.join(", ", meals) + "입니다.");

        if (!visits.isEmpty()) {
            String count = "방문 " + visits.size() + "곳" + (foodVisits > 0 ? "(식당·카페 " + foodVisits + "곳 포함)" : "");
            SimulatedItinerary lastItem = items.get(items.size() - 1);
            String arrive = lastItem.getType() != SimulatedItinerary.Type.END ? " 종료" : departure ? " 공항 도착" : " 숙소 도착";
            sentences.add(count + ", 이동 약 " + duration(travel) + ", " + summary.getStartTime() + " 출발 · " + summary.getEndTime() + arrive + ".");
        }
        if (Boolean.TRUE.equals(badWeather)) sentences.add("비·눈 예보가 있어 실내 장소를 우선했습니다.");

        summary.setSummary(String.join(" ", sentences));
        return summary;
    }

    // =====================================================================
    // 여행 전체
    // =====================================================================

    /** 예: "시즈오카·하마마쓰 5박 6일 온천·자연 여행" */
    public static String tripTitle(PlanRequest request, List<DayPlan> days) {
        List<String> themes = ThemeVocabulary.normalizeAll(request.getThemes());
        String themeLabel = themes.isEmpty() ? "" : String.join("·", themes.subList(0, Math.min(2, themes.size()))) + " ";
        return cityLabel(request.getCities()) + " " + nights(days.size()) + " " + themeLabel + "여행";
    }

    /** 여행 전체 요약 문단 */
    public static String tripOverview(PlanRequest request, List<DayPlan> days) {
        List<String> sentences = new ArrayList<>();
        List<String> cities = request.getCities() == null ? new ArrayList<>() : request.getCities();

        // 1. 누가, 어디로, 얼마나
        sentences.add(companionPhrase(request.getCompanion()) + cityLabel(cities) + " " + nights(days.size()) + " 여행입니다.");

        // 2. 무엇을 (테마와 방문 수)
        List<SimulatedItinerary> visits = days.stream().flatMap(d -> d.getItems().stream())
                .filter(i -> i.getType() == SimulatedItinerary.Type.VISIT && i.getPlace() != null)
                .collect(Collectors.toList());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String label : new String[]{"관광지", "식당", "카페", "쇼핑"}) counts.put(label, 0);
        Set<String> seen = new LinkedHashSet<>();
        for (SimulatedItinerary v : visits) {
            if (!seen.add(v.getPlace().getPlaceId() != null ? v.getPlace().getPlaceId() : v.getPlace().getName())) continue;
            counts.merge(kindLabel(PlaceKind.of(v.getPlace())), 1, Integer::sum);
        }
        List<String> countParts = counts.entrySet().stream().filter(e -> e.getValue() > 0)
                .map(e -> e.getKey() + " " + e.getValue() + "곳").collect(Collectors.toList());
        List<String> themes = ThemeVocabulary.normalizeAll(request.getThemes());
        if (!countParts.isEmpty()) {
            sentences.add((themes.isEmpty() ? "" : String.join("·", themes) + " 테마를 중심으로 ")
                    + String.join(", ", countParts) + "을 담았습니다.");
        }

        // 3. 어떤 순서로 (도시가 둘 이상일 때)
        List<String> legs = cityLegs(days);
        if (legs.size() > 1) sentences.add("일정은 " + String.join(" → ", legs) + " 순서로 이어집니다.");

        // 4. 대표 방문지
        List<SimulatedItinerary> sights = visits.stream().filter(i -> !PlaceKind.of(i.getPlace()).isFood()).collect(Collectors.toList());
        List<String> top = highlights(sights, MAX_HIGHLIGHTS_PER_TRIP);
        if (!top.isEmpty()) sentences.add("대표 방문지는 " + String.join(", ", top) + "입니다.");

        // 5. 숙소
        List<String> lodgings = lodgingLegs(days);
        if (!lodgings.isEmpty()) sentences.add("숙소는 " + String.join(", ", lodgings) + "입니다.");

        // 6. 입·출국
        Place in = anchor(days, true);
        Place out = anchor(days, false);
        if (in != null && out != null) {
            sentences.add(in.getName().equals(out.getName())
                    ? "입·출국은 모두 " + in.getName() + " 기준입니다."
                    : in.getName() + josa(in.getName(), "으로", "로") + " 들어가 " + out.getName() + "에서 나오는 일정입니다.");
        } else if (in != null) {
            sentences.add("입국은 " + in.getName() + " 기준입니다.");
        } else if (out != null) {
            sentences.add("출국은 " + out.getName() + " 기준입니다.");
        }
        return String.join(" ", sentences);
    }

    // =====================================================================
    // 내부 계산
    // =====================================================================

    /**
     * 그 날 비중이 큰 테마 (머문 시간 기준, 많은 순).
     * '관광'은 거의 모든 관광지에 붙는 넓은 말이라 절반만 치고, '맛집'·'카페'는 식당·카페에서만 센다.
     */
    private static List<String> dayThemes(List<SimulatedItinerary> visits) {
        Map<String, Double> weight = new LinkedHashMap<>();
        for (String t : ThemeVocabulary.THEMES) weight.put(t, 0.0);
        for (SimulatedItinerary v : visits) {
            boolean food = PlaceKind.of(v.getPlace()).isFood();
            int minutes = v.getEndMin() == null ? 60 : Math.max(15, v.getEndMin() - v.getStartMin());
            for (String theme : PlaceDescriber.themeListOf(v.getPlace())) {
                boolean foodTheme = "맛집".equals(theme) || "카페".equals(theme);
                if (foodTheme != food) continue;
                double factor = food ? 0.6 : "관광".equals(theme) ? 0.5 : 1.0;
                weight.merge(theme, minutes * factor, Double::sum);
            }
        }
        return weight.entrySet().stream().filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))   // 안정 정렬: 같으면 어휘 순서
                .limit(MAX_THEMES_PER_DAY).map(Map.Entry::getKey).collect(Collectors.toList());
    }

    /** 리뷰 수가 많은(잘 알려진) 곳부터 고른 뒤, 방문 순서대로 돌려준다. 같은 장소는 한 번만. */
    private static List<String> highlights(List<SimulatedItinerary> sights, int max) {
        List<SimulatedItinerary> distinct = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (SimulatedItinerary s : sights) {
            if (names.add(s.getPlace().getName())) distinct.add(s);
        }
        List<SimulatedItinerary> ranked = new ArrayList<>(distinct);
        ranked.sort(Comparator.comparingInt((SimulatedItinerary s) -> reviewCount(s.getPlace())).reversed());   // 안정 정렬
        Set<SimulatedItinerary> picked = new LinkedHashSet<>(ranked.subList(0, Math.min(max, ranked.size())));
        return distinct.stream().filter(picked::contains).map(s -> s.getPlace().getName()).collect(Collectors.toList());
    }

    private static int reviewCount(Place p) {
        return p.getUserRatingCount() == null ? 0 : p.getUserRatingCount();
    }

    private static List<String> distinctSubTypes(List<SimulatedItinerary> sights, int max) {
        Set<String> out = new LinkedHashSet<>();
        for (SimulatedItinerary s : sights) {
            String subType = PlaceDescriber.subTypeOf(s.getPlace());
            if (subType != null && !subType.isBlank()) out.add(subType);
            if (out.size() == max) break;
        }
        if (out.isEmpty()) out.add("관광 명소");
        return new ArrayList<>(out);
    }

    /** "점심 교자", "저녁 장어", "카페", "저녁 자유 식사" */
    private static List<String> mealLabels(List<SimulatedItinerary> items) {
        List<String> out = new ArrayList<>();
        Set<String> usedSlots = new LinkedHashSet<>();
        for (SimulatedItinerary item : items) {
            if (item.getType() == SimulatedItinerary.Type.MEAL) {
                out.add(slot(item.getStartMin(), usedSlots) + " 자유 식사");
                continue;
            }
            if (item.getType() != SimulatedItinerary.Type.VISIT || item.getPlace() == null) continue;
            PlaceKind kind = PlaceKind.of(item.getPlace());
            if (!kind.isFood()) continue;

            String subType = PlaceDescriber.subTypeOf(item.getPlace());
            if (kind == PlaceKind.CAFE) {
                // 카페는 아침 식사를 대신할 때만 끼니로 적는다
                out.add(item.getStartMin() < 10 * 60 + 30 && usedSlots.add("아침") ? "아침 " + subType : subType);
            } else {
                out.add(slot(item.getStartMin(), usedSlots) + " " + ("식당".equals(subType) ? "현지 식당" : subType));
            }
        }
        return out;
    }

    /** 시작 시각으로 본 끼니. 같은 끼니가 이미 있으면 그다음 것(점심 뒤 간식, 저녁 뒤 야식)으로 적는다. */
    private static String slot(int startMin, Set<String> used) {
        String[] order = {"아침", "점심", "간식", "저녁", "야식"};
        int index = startMin < 10 * 60 + 30 ? 0 : startMin <= 14 * 60 + 30 ? 1 : startMin < 17 * 60 ? 2 : startMin < 20 * 60 + 30 ? 3 : 4;
        // 오후 늦게 도착한 날처럼 첫 끼가 늦어진 경우: 간식·야식이 아니라 늦은 점심·늦은 저녁이다
        if (index == 2 && !used.contains("점심") && startMin < 16 * 60 + 30) {
            used.add("점심");
            return "늦은 점심";
        }
        if (index == 4 && !used.contains("저녁")) {
            used.add("저녁");
            return "늦은 저녁";
        }
        while (index < order.length - 1 && used.contains(order[index])) index++;
        used.add(order[index]);
        return order[index];
    }

    private static String dominantCity(List<SimulatedItinerary> visits) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SimulatedItinerary v : visits) {
            if (v.getPlace().getCity() != null) counts.merge(v.getPlace().getCity(), 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** "시즈오카(1~3일차)", "하마마쓰(4~5일차)" */
    private static List<String> cityLegs(List<DayPlan> days) {
        List<String> legs = new ArrayList<>();
        int from = 0;
        for (int i = 1; i <= days.size(); i++) {
            String current = days.get(from).getCity();
            if (i < days.size() && same(current, days.get(i).getCity())) continue;
            if (current != null) legs.add(current + "(" + range(days.get(from).getDayNumber(), days.get(i - 1).getDayNumber()) + "일차)");
            from = i;
        }
        return legs;
    }

    /** 밤마다 돌아가는 숙소를 이어서 묶는다: "A 호텔(1~3일차 숙박)" */
    private static List<String> lodgingLegs(List<DayPlan> days) {
        List<String> names = new ArrayList<>();   // 일차별 숙소 이름 (없으면 null)
        for (DayPlan day : days) {
            List<SimulatedItinerary> items = day.getItems();
            SimulatedItinerary end = items.isEmpty() ? null : items.get(items.size() - 1);
            boolean lodging = end != null && end.getType() == SimulatedItinerary.Type.END
                    && end.getPlace() != null && !PlanService.isAirport(end.getPlace());
            names.add(lodging ? end.getPlace().getName() : null);
        }
        List<String> legs = new ArrayList<>();
        int from = 0;
        for (int i = 1; i <= names.size(); i++) {
            if (i < names.size() && same(names.get(from), names.get(i))) continue;
            if (names.get(from) != null) {
                legs.add(names.get(from) + "(" + range(days.get(from).getDayNumber(), days.get(i - 1).getDayNumber()) + "일차 숙박)");
            }
            from = i;
        }
        // 숙소가 한 곳뿐이면 일차 표시는 군더더기다
        if (legs.size() == 1) return List.of(names.stream().filter(n -> n != null).findFirst().orElse(""));
        return legs;
    }

    private static Place anchor(List<DayPlan> days, boolean arrival) {
        if (days.isEmpty()) return null;
        List<SimulatedItinerary> items = days.get(arrival ? 0 : days.size() - 1).getItems();
        if (items.isEmpty()) return null;
        SimulatedItinerary item = items.get(arrival ? 0 : items.size() - 1);
        SimulatedItinerary.Type expected = arrival ? SimulatedItinerary.Type.START : SimulatedItinerary.Type.END;
        return item.getType() == expected && PlanService.isAirport(item.getPlace()) ? item.getPlace() : null;
    }

    private static String kindLabel(PlaceKind kind) {
        switch (kind) {
            case RESTAURANT:
            case BAR:
                return "식당";
            case CAFE:
                return "카페";
            case SHOPPING:
                return "쇼핑";
            default:
                return "관광지";
        }
    }

    // =====================================================================
    // 문구
    // =====================================================================

    private static String companionPhrase(String companion) {
        if (companion == null) return "";
        String c = companion.trim();
        if (c.contains("혼자") || c.contains("나홀로") || c.contains("솔로")) return "혼자 떠나는 ";
        if (c.contains("연인") || c.contains("커플")) return "연인과 함께하는 ";
        if (c.contains("부모")) return "부모님과 함께하는 ";
        if (c.contains("아이") || c.contains("유아")) return "아이와 함께하는 ";
        if (c.contains("가족")) return "가족과 함께하는 ";
        if (c.contains("친구")) return "친구와 함께하는 ";
        return "";
    }

    private static String cityLabel(List<String> cities) {
        if (cities == null || cities.isEmpty()) return "일본";
        if (cities.size() <= 3) return String.join("·", cities);
        return cities.get(0) + "·" + cities.get(1) + " 외 " + (cities.size() - 2) + "곳";
    }

    private static String nights(int totalDays) {
        return totalDays <= 1 ? "당일치기" : (totalDays - 1) + "박 " + totalDays + "일";
    }

    private static String range(int from, int to) {
        return from == to ? String.valueOf(from) : from + "~" + to;
    }

    private static String duration(int minutes) {
        if (minutes < 60) return minutes + "분";
        return minutes % 60 == 0 ? (minutes / 60) + "시간" : (minutes / 60) + "시간 " + (minutes % 60) + "분";
    }

    private static String dayOfWeek(DayOfWeek d) {
        return new String[]{"월", "화", "수", "목", "금", "토", "일"}[d.getValue() - 1];
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String join(String separator, String a, String b) {
        return a == null || a.isEmpty() ? b : a + separator + b;
    }

    /**
     * 받침에 따라 조사를 고른다. josa("공원", "을", "를") → "을", josa("교토", "으로", "로") → "로".
     * 한글로 끝나지 않는 말(영문·숫자·괄호)은 받침 없는 쪽을 쓴다.
     */
    static String josa(String word, String withBatchim, String withoutBatchim) {
        if (word == null || word.isEmpty()) return withoutBatchim;
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) return withoutBatchim;
        int jong = (last - 0xAC00) % 28;
        if (jong == 0) return withoutBatchim;
        if (jong == 8 && "으로".equals(withBatchim)) return withoutBatchim;   // ㄹ 받침 + (으)로 → "로"
        return withBatchim;
    }
}
