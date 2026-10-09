package com.travel.planner.util;

import com.travel.planner.entity.Place;

import java.util.regex.Pattern;

/** 일정 엔진이 하루 한도·식사 시간대를 판단할 때 쓰는 장소 유형. */
public enum PlaceKind {
    ATTRACTION, RESTAURANT, CAFE, BAR, SHOPPING, THEME_PARK;

    private static final Pattern CAFE_NAME = Pattern.compile(
            "카페|cafe|café|coffee|커피|珈琲|喫茶|스타벅스|starbucks|베이커리|bakery|디저트|파티스리|티룸|찻집|\\btea\\b|블루보틀|도토루|코메다");
    // 분류가 '식음'으로 들어가 있지만 실제로는 식당이 아닌 곳 (슈퍼마켓·편의점 등)
    private static final Pattern GROCERY_NAME = Pattern.compile(
            "슈퍼|(?<!스)마트(\\s|$)|supermarket|편의점|\\bvalor\\b|\\baeon\\b|(^|\\s)이온(\\s|몰|$)|세이유|\\bseiyu\\b|로손|\\blawson\\b|세븐일레븐|7-eleven|패밀리마트|familymart|돈키호테|업무슈퍼");
    private static final Pattern LODGING_NAME = Pattern.compile(
            "호텔|hotel|ホテル|료칸|旅館|\\binn\\b|게스트하우스|guest ?house|호스텔|hostel|민박|펜션|도요코인|toyoko|apa ");
    private static final Pattern STATION_NAME = Pattern.compile("(?<![구지유])역$|駅$|\\bstation$");
    // 해가 있을 때 가야 의미가 있는 야외 장소 (공원·정원·해변·신사 등)
    private static final Pattern PARK_NAME = Pattern.compile("공원|\\bpark\\b|公園|정원|\\bgarden\\b|庭園|광장");
    private static final Pattern OPEN_AIR_NAME = Pattern.compile(
            "해변|\\bbeach\\b|해안|호수|폭포|산책로|신사|神社|\\bshrine\\b|신궁|\\btemple\\b|寺$|동상|\\s상$|像$|\\bstatue\\b|기념비|\\bmonument\\b");
    // AI 가 채운 세부 유형(Place.subType)으로 본 디저트·음료 가게
    private static final Pattern DESSERT_TYPE = Pattern.compile(
            "카페|커피|디저트|젤라토|아이스크림|베이커리|빵집|제과|찻집|티룸|파르페|빙수|크레페|도넛|케이크|스위츠|화과자|과자|푸딩|타르트|주스|버블티");
    private static final Pattern MEAL_TYPE = Pattern.compile("식당|레스토랑|정식|런치|브런치|다이닝");
    private static final Pattern BAR_NAME = Pattern.compile(
            "이자카야|居酒屋|술집|\\b(bar|pub|beer)\\b|펍|비어|야타이|스탠딩바|하이볼");
    private static final Pattern THEME_PARK_NAME = Pattern.compile(
            "유니버설 스튜디오|유니버셜 스튜디오|universal studios|\\busj\\b|디즈니랜드|디즈니씨|디즈니 씨|disneyland|disneysea|레고랜드|legoland|후지큐 하이랜드|하우스텐보스|지브리 파크|나가시마 스파랜드");

    public static PlaceKind of(Place p) {
        String name = p.getName() == null ? "" : p.getName().toLowerCase();
        String category = p.getCategory() == null ? "" : p.getCategory();
        String theme = p.getTheme() == null ? "" : p.getTheme();

        if ("테마파크".equals(category)) return THEME_PARK;
        // "디즈니 스토어", "유니버설 시티워크" 같은 상점·식당이 테마파크로 오인되지 않도록 관광지 계열에서만 이름을 본다.
        boolean attractionLike = category.isEmpty() || "관광지".equals(category);
        if (attractionLike && THEME_PARK_NAME.matcher(name).find()) return THEME_PARK;
        if ("식음".equals(category)) {
            if (CAFE_NAME.matcher(name).find()) return CAFE;
            // 이름에는 단서가 없지만 AI 가 채운 세부 유형이 디저트·젤라토·빵집인 가게는 끼니를 해결하는 식당이 아니다
            // (예: 말차 젤라토 가게가 '맛집' 테마로 분류돼 점심 식당으로 들어가던 문제)
            String subType = p.getSubType() == null ? "" : p.getSubType().toLowerCase();
            if (DESSERT_TYPE.matcher(subType).find() && !MEAL_TYPE.matcher(subType).find()) return CAFE;
            if (GROCERY_NAME.matcher(name).find()) return SHOPPING;
            if (BAR_NAME.matcher(name).find()) return BAR;
            if (theme.contains("카페") && !theme.contains("맛집")) return CAFE;
            return RESTAURANT;
        }
        if ("쇼핑".equals(category)) {
            // 쇼핑몰·가전매장 안에 있어 '쇼핑'으로 분류됐지만 테마는 맛집뿐인 곳(예: "우나기 ○○ 빅카메라점")은 식당이다
            if (theme.contains("맛집") && !theme.contains("쇼핑")) return RESTAURANT;
            return SHOPPING;
        }
        return ATTRACTION;
    }

    /** 이름으로 본 숙박 시설 여부. DB 에 분류가 비어 있는 호텔이 방문지로 들어가는 것을 막는 데 쓴다. */
    public static boolean looksLikeLodging(String name) {
        return name != null && LODGING_NAME.matcher(name.toLowerCase()).find();
    }

    /** 이름으로 본 역 여부 ("누마즈역", "Shizuoka Station"). "역사박물관"처럼 '역'으로 끝나지 않는 이름은 해당하지 않는다. */
    public static boolean looksLikeStation(String name) {
        return name != null && STATION_NAME.matcher(name.trim().toLowerCase()).find();
    }

    /** 이름으로 본 공원·정원 여부 */
    public static boolean looksLikePark(String name) {
        return name != null && PARK_NAME.matcher(name.toLowerCase()).find();
    }

    /** 이름으로 본 야외 명소 여부 (공원·정원·해변·호수·신사 등). 테마에 '자연'이 없어도 해 진 뒤에는 넣지 않기 위해 쓴다. */
    public static boolean looksLikeOpenAir(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase();
        return PARK_NAME.matcher(n).find() || OPEN_AIR_NAME.matcher(n).find();
    }

    private static final String[][] CUISINES = {
            {"스시", "초밥", "寿司", "鮨", "sushi", "즈시"}, {"라멘", "ラーメン", "ramen"}, {"소바", "そば", "蕎麦", "soba"},
            {"우동", "うどん", "udon"}, {"교자", "餃子", "gyoza"}, {"야키니쿠", "焼肉", "yakiniku"}, {"돈카츠", "とんかつ", "tonkatsu"},
            {"우나기", "うなぎ", "鰻", "장어", "unagi"}, {"카레", "カレー", "curry"}, {"햄버그", "ハンバーグ", "hamburg", "사와야카"},
            {"오뎅", "おでん", "oden"}, {"텐동", "덴푸라", "天ぷら", "tempura"}, {"오코노미야키", "お好み焼", "okonomiyaki"},
            {"타코야키", "たこ焼"}, {"피자", "pizza"}, {"파스타", "pasta"}, {"야키토리", "焼鳥", "焼き鳥", "yakitori"}};

    /** 이름으로 본 음식 종류(스시·라멘 등). 알 수 없으면 null. 하루에 같은 종류를 두 번 넣지 않는 데 쓴다. */
    public static String cuisineOf(String name) {
        if (name == null) return null;
        String n = name.toLowerCase();
        for (String[] group : CUISINES) {
            for (String keyword : group) {
                if (n.contains(keyword)) return group[0];
            }
        }
        return null;
    }

    public boolean isFood() {
        return this == RESTAURANT || this == CAFE || this == BAR;
    }
}
