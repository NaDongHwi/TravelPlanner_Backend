package com.travel.planner.service;

import com.travel.planner.entity.Place;

import java.util.ArrayList;
import java.util.List;

/** 테스트용 가상 장소 데이터 (오사카·교토 주요 권역 좌표 기반). */
final class TestPlaces {

    private static final String[] DAYS = {"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};

    static final String H_9_17 = week("오전 9:00 ~ 오후 5:00");
    static final String H_10_22 = week("오전 10:00 ~ 오후 10:00");
    static final String H_RESTAURANT = week("오전 11:00 ~ 오후 3:00, 오후 5:00 ~ 10:00");
    static final String H_CAFE = week("오전 8:00 ~ 오후 8:00");
    static final String H_SHOP = week("오전 10:00 ~ 오후 9:00");
    static final String H_IZAKAYA = week("오후 5:00 ~ 오전 12:00");
    static final String H_ONSEN = week("오전 10:00 ~ 오후 11:00");
    static final String H_USJ = week("오전 9:00 ~ 오후 8:00");
    static final String H_24 = week("24시간 영업");

    private TestPlaces() {}

    static String week(String daily) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DAYS.length; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(DAYS[i]).append(": ").append(daily);
        }
        return sb.toString();
    }

    static String weekClosedOn(String daily, String closedDay) {
        return week(daily).replace(closedDay + ": " + daily, closedDay + ": 휴무일");
    }

    static Place place(String id, String name, String city, double lat, double lng, String category, String theme, String hours) {
        Place p = new Place();
        p.setPlaceId(id);
        p.setName(name);
        p.setCity(city);
        p.setLatitude(lat);
        p.setLongitude(lng);
        p.setCategory(category);
        p.setTheme(theme);
        p.setOpeningHours(hours);
        return p;
    }

    static Place find(List<Place> places, String name) {
        return places.stream().filter(p -> p.getName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("테스트 데이터에 없음: " + name));
    }

    private static Place add(List<Place> out, String city, String name, double lat, double lng, String category,
                             String theme, String type, String hours, Integer duration) {
        Place p = place("T_" + city + "_" + name.replace(' ', '_'), name, city, lat, lng, category, theme, hours);
        p.setPlaceType(type);
        p.setRecommendedDuration(duration);
        p.setRating(4.2);
        p.setUserRatingCount(1500);
        out.add(p);
        return p;
    }

    /** 권역 하나에 식당 6·카페 2·상점 2 를 뿌린다. */
    private static void commerce(List<Place> out, String city, String district, double lat, double lng) {
        String[] foods = {"라멘", "스시", "오코노미야키", "우동", "야키니쿠", "돈카츠"};
        for (int i = 0; i < foods.length; i++) {
            add(out, city, district + " " + foods[i], lat + (i % 3 - 1) * 0.0025, lng + (i % 2 == 0 ? 0.002 : -0.002),
                    "식음", "맛집", "실내", i == 4 ? null : H_RESTAURANT, 60);
        }
        add(out, city, district + " 커피 로스터스", lat + 0.0015, lng + 0.0030, "식음", "카페", "실내", H_CAFE, 45);
        add(out, city, district + " 디저트 카페", lat - 0.0018, lng - 0.0027, "식음", "카페,사진", "실내", H_CAFE, 45);
        add(out, city, district + " 이자카야 골목집", lat - 0.0008, lng + 0.0012, "식음", "맛집", "실내", H_IZAKAYA, 75);
        add(out, city, district + " 쇼핑몰", lat + 0.0022, lng - 0.0015, "쇼핑", "쇼핑", "실내", H_SHOP, 90);
        add(out, city, "돈키호테 " + district + "점", lat - 0.0025, lng + 0.0021, "쇼핑", "쇼핑", "실내", H_24, 60);
    }

    static List<Place> osaka() {
        List<Place> out = new ArrayList<>();
        String c = "오사카";

        // 난바 / 도톤보리
        double la = 34.6687, lo = 135.5013;
        add(out, c, "도톤보리 거리", la, lo, "관광지", "관광,사진,야경", "실외", null, 60);
        add(out, c, "난바 역사 박물관", la + 0.002, lo - 0.003, "관광지", "문화", "실내", weekClosedOn("오전 9:00 ~ 오후 5:00", "월요일"), 90);
        add(out, c, "호젠지 요코초", la - 0.001, lo + 0.001, "관광지", "관광,사진", "실외", null, 40);
        add(out, c, "난바 야사카 신사", la - 0.006, lo - 0.004, "관광지", "문화,사진", "실외", H_9_17, 40);
        commerce(out, c, "난바", la, lo);

        // 덴덴타운 (서브컬쳐)
        la = 34.6592; lo = 135.5060;
        add(out, c, "덴덴타운 애니 스트리트", la, lo, "관광지", "서브컬쳐,쇼핑", "실외", H_10_22, 90);
        add(out, c, "피규어 뮤지엄 오사카", la + 0.001, lo + 0.001, "관광지", "서브컬쳐", "실내", H_10_22, 60);
        add(out, c, "레트로 게임 센터", la - 0.001, lo - 0.001, "관광지", "서브컬쳐,액티비티", "실내", H_10_22, 60);

        // 우메다
        la = 34.7025; lo = 135.4959;
        add(out, c, "우메다 스카이 전망대", la + 0.003, lo - 0.006, "관광지", "야경,사진", "실외", H_10_22, 75);
        add(out, c, "우메다 미술관", la - 0.002, lo + 0.003, "관광지", "문화", "실내", H_9_17, 90);
        add(out, c, "헵파이브 대관람차", la + 0.001, lo + 0.003, "관광지", "야경,액티비티", "실외", H_10_22, 40);
        add(out, c, "나카노시마 장미 정원", la - 0.010, lo + 0.008, "관광지", "자연,힐링", "실외", null, 60);
        commerce(out, c, "우메다", la, lo);

        // 오사카성
        la = 34.6873; lo = 135.5262;
        add(out, c, "오사카 성", la, lo, "관광지", "문화,관광,사진", "실외", H_9_17, 90);
        add(out, c, "오사카 성 공원", la + 0.002, lo + 0.002, "관광지", "자연,힐링", "실외", null, 60);
        add(out, c, "오사카 역사 박물관", la - 0.004, lo - 0.005, "관광지", "문화", "실내", weekClosedOn("오전 9:00 ~ 오후 5:00", "화요일"), 90);
        commerce(out, c, "모리노미야", la - 0.003, lo + 0.006);

        // 덴노지 / 신세카이
        la = 34.6466; lo = 135.5133;
        add(out, c, "덴노지 정원", la + 0.003, lo - 0.002, "관광지", "자연,힐링", "실외", H_9_17, 60);
        add(out, c, "시텐노지", la + 0.008, lo + 0.003, "관광지", "문화", "실외", H_9_17, 60);
        add(out, c, "아베노 하루카스 전망대", la - 0.001, lo + 0.001, "관광지", "야경,사진", "실내", H_10_22, 75);
        add(out, c, "쓰텐카쿠", la + 0.006, lo - 0.007, "관광지", "관광,야경", "실내", H_10_22, 60);
        add(out, c, "스파월드 온천", la + 0.004, lo - 0.008, "관광지", "온천,힐링", "실내", H_ONSEN, 120);
        add(out, c, "신세카이 혼도리", la + 0.006, lo - 0.006, "관광지", "관광,사진", "실외", null, 45);
        commerce(out, c, "덴노지", la, lo);

        // 베이 에어리어
        la = 34.6545; lo = 135.4290;
        add(out, c, "베이 수족관", la, lo, "관광지", "액티비티,관광", "실내", week("오전 10:00 ~ 오후 5:00"), 120);
        add(out, c, "덴포잔 대관람차", la + 0.001, lo + 0.002, "관광지", "야경,사진", "실외", H_10_22, 40);
        add(out, c, "유니버설 스튜디오 재팬", 34.6654, 135.4323, "관광지", "액티비티", "실외", H_USJ, null);
        add(out, c, "소라니와 온천", la + 0.006, lo + 0.012, "관광지", "온천", "실내", H_ONSEN, 120);
        commerce(out, c, "덴포잔", la, lo);

        // 후보에 섞여 들어오면 안 되는 것들
        add(out, c, "난바 그랜드 호텔", 34.6670, 135.5000, "숙소", null, null, null, null);
        add(out, c, "난바역", 34.6661, 135.5003, "교통", null, null, null, null);
        add(out, c, "간사이 국제공항 전망홀", 34.4347, 135.2440, "관광지", "관광", "실내", H_10_22, 60);
        return out;
    }

    static List<Place> kyoto() {
        List<Place> out = new ArrayList<>();
        String c = "교토";

        double la = 35.0037, lo = 135.7788;   // 기온 / 히가시야마
        add(out, c, "기요미즈데라", la - 0.009, lo + 0.006, "관광지", "문화,사진", "실외", week("오전 6:00 ~ 오후 6:00"), 90);
        add(out, c, "야사카 신사", la, lo, "관광지", "문화", "실외", null, 40);
        add(out, c, "마루야마 공원", la + 0.001, lo + 0.003, "관광지", "자연,힐링", "실외", null, 45);
        add(out, c, "기온 하나미코지", la - 0.001, lo - 0.003, "관광지", "관광,사진", "실외", null, 45);
        add(out, c, "철학의 길", la + 0.020, lo + 0.015, "관광지", "자연,힐링", "실외", null, 60);
        commerce(out, c, "기온", la, lo - 0.004);

        la = 35.0094; lo = 135.6668;          // 아라시야마
        add(out, c, "아라시야마 대나무 숲", la + 0.007, lo + 0.005, "관광지", "자연,사진", "실외", null, 60);
        add(out, c, "덴류지", la + 0.006, lo + 0.007, "관광지", "문화,자연", "실외", week("오전 8:30 ~ 오후 5:00"), 60);
        add(out, c, "도게츠교", la + 0.003, lo + 0.011, "관광지", "자연,사진", "실외", null, 30);
        commerce(out, c, "아라시야마", la + 0.004, lo + 0.010);

        la = 34.9671; lo = 135.7727;          // 후시미
        add(out, c, "후시미 이나리 신사", la, lo, "관광지", "문화,사진", "실외", H_24, 120);
        add(out, c, "교토 국립 박물관", 34.9900, 135.7731, "관광지", "문화", "실내", weekClosedOn("오전 9:30 ~ 오후 5:00", "월요일"), 90);
        commerce(out, c, "교토역", 34.9858, 135.7588);
        return out;
    }

    /**
     * 실제 시즈오카 일정에서 문제가 됐던 데이터 모양을 그대로 옮긴 것:
     * 분류가 비어 있는 호텔·역, 60km 넘게 떨어진 온천, 산속에 홀로 있는 명소, '식음'으로 들어간 슈퍼마켓.
     */
    static List<Place> shizuoka() {
        List<Place> out = new ArrayList<>();
        String c = "시즈오카";

        // 시내 (시즈오카역 주변)
        add(out, c, "슨푸 성 공원", 34.9790, 138.3831, "관광지", "자연,힐링", "실외", H_24, null);
        add(out, c, "도키와 공원", 34.9702, 138.3803, "관광지", "자연,힐링", null, H_24, null);
        add(out, c, "슨푸성 모미지야마 정원", 34.9800, 138.3849, "관광지", "자연,힐링", "실외", week("오전 9:00 ~ 오후 4:00"), null);
        add(out, c, "시즈오카 센겐 신사", 34.9836, 138.3757, "관광지", "문화,힐링", "실외", H_9_17, null);
        add(out, c, "시즈오카 시립 미술관", 34.9725, 138.3880, "관광지", "문화", "실내", H_9_17, null);
        add(out, c, "사누키우동 마루도", 34.9762, 138.3873, "식음", "맛집", "실내", week("오전 11:00 ~ 오후 3:00"), null);
        add(out, c, "시즈오카오뎅 미카와야", 34.9723, 138.3818, "식음", "맛집", "실내", week("오후 5:00 ~ 10:00"), null);
        add(out, c, "우나기노 하라가와", 34.9745, 138.3844, "식음", "맛집", "실내", week("오전 11:00 ~ 오후 2:00, 오후 5:00 ~ 7:30"), null);
        add(out, c, "GOOD TIMING TEA", 34.9765, 138.3896, "식음", null, "실내", week("오전 8:00 ~ 오후 9:00"), null);
        commerce(out, c, "고후쿠초", 34.9745, 138.3830);

        // 히가시시즈오카 / 구사나기
        add(out, c, "아오이 온천 쿠사나기노유", 35.0054, 138.4442, "관광지", "온천,힐링", "실내", week("오전 9:00 ~ 오후 11:00"), null);
        add(out, c, "Higashishizuoka Smile Park", 34.9880, 138.4157, "관광지", "자연", null, H_24, null);
        add(out, c, "도로 공원", 34.9560, 138.4088, "관광지", "자연,문화", null, H_24, null);
        add(out, c, "스시로 히가시 시즈오카점", 34.9913, 138.4195, "식음", "맛집", "실내", week("오전 10:30 ~ 오후 11:00"), null);
        add(out, c, "Valor Fujimidai Shop", 34.9620, 138.4156, "식음", null, null, week("오전 9:30 ~ 오후 9:00"), null);

        // 니혼다이라 / 미호 (시내에서 10~13km, 볼거리가 모여 있는 근교)
        add(out, c, "니혼다이라 유메테라스", 34.9725, 138.4675, "관광지", "자연,사진", "실외", H_9_17, null);
        add(out, c, "구노잔 도쇼구", 34.9647, 138.4676, "관광지", "문화", "실외", H_9_17, null);
        add(out, c, "미호노 마쓰바라", 34.9953, 138.5220, "관광지", "자연,힐링", null, H_24, null);
        add(out, c, "니혼다이라 동물원", 34.9870, 138.4380, "관광지", "자연,액티비티", "실외", H_9_17, null);
        commerce(out, c, "시미즈", 35.0150, 138.4890);

        // 분류가 비어 있어 방문지로 섞여 들어오던 숙소·역
        add(out, c, "Hotel Ole Inn", 34.9713, 138.3825, null, null, null, null, null);
        add(out, c, "누마즈 리버 사이드 호텔", 35.0969, 138.8585, null, null, null, null, null);
        add(out, c, "누마즈역", 35.1026, 138.8598, null, null, null, null, null);

        // 멀리 홀로 떨어진 곳들 (시내에서 35~67km)
        add(out, c, "천연온천 자분", 35.1298, 138.8005, "관광지", "온천,힐링", "실내", week("오전 9:00 ~ 오전 2:00"), null);
        add(out, c, "오부치 사사바", 35.2198, 138.6974, "관광지", "자연,사진", null, H_24, null);
        add(out, c, "梅ヶ島新田温泉 黄金の湯", 35.2854, 138.3400, "관광지", "온천,힐링", "실내", week("오전 9:30 ~ 오후 5:00"), null);
        add(out, c, "타누키 호수 전망대", 35.3418, 138.5541, "관광지", "자연,사진", null, H_24, null);
        add(out, c, "나라다노사토 온천", 35.5764, 138.3018, "관광지", "온천,힐링", "실내", week("오전 9:00 ~ 오후 7:00"), null);
        return out;
    }
}
