package com.travel.planner.util;

import com.travel.planner.entity.Place;

import java.util.regex.Pattern;

/** 일정 엔진이 하루 한도·식사 시간대를 판단할 때 쓰는 장소 유형. */
public enum PlaceKind {
    ATTRACTION, RESTAURANT, CAFE, BAR, SHOPPING, THEME_PARK;

    private static final Pattern CAFE_NAME = Pattern.compile(
            "카페|cafe|café|coffee|커피|珈琲|喫茶|스타벅스|starbucks|베이커리|bakery|디저트|파티스리|티룸|찻집|블루보틀|도토루|코메다");
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
            if (BAR_NAME.matcher(name).find()) return BAR;
            if (theme.contains("카페") && !theme.contains("맛집")) return CAFE;
            return RESTAURANT;
        }
        if ("쇼핑".equals(category)) return SHOPPING;
        return ATTRACTION;
    }

    public boolean isFood() {
        return this == RESTAURANT || this == CAFE || this == BAR;
    }
}
