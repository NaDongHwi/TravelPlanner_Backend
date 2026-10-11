package com.travel.planner.util;

import com.travel.planner.entity.Place;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 장소 이름 가운데 한국어로 옮겨야 하는 것을 가려내고, AI 가 돌려준 이름을 걸러 내는 규칙 테스트 */
class PlaceNamesTest {

    @Test
    void namesWithoutHangulOrWithJapaneseLettersNeedAKoreanName() {
        assertTrue(PlaceNames.needsKorean("Hotel Associa Shizuoka"));
        assertTrue(PlaceNames.needsKorean("駿府城公園"));
        assertTrue(PlaceNames.needsKorean("ドン・キホーテ 静岡店"));
        assertTrue(PlaceNames.needsKorean("三保の松原"));
        assertTrue(PlaceNames.needsKorean("라멘 一蘭 시즈오카점"), "한글과 한자가 섞인 이름도 옮긴다");

        assertFalse(PlaceNames.needsKorean("슨푸성 공원"));
        assertFalse(PlaceNames.needsKorean("이온몰 AEON MALL"), "한글이 있고 일본어 글자가 없으면 그대로 둔다");
        assertFalse(PlaceNames.needsKorean("간사이 국제공항"));
        assertFalse(PlaceNames.needsKorean("109"), "숫자뿐인 이름은 옮길 것이 없다");
        assertFalse(PlaceNames.needsKorean(""));
        assertFalse(PlaceNames.needsKorean(null));
    }

    @Test
    void answersFromAiAreCleanedOrRejected() {
        assertEquals("슨푸성 공원", PlaceNames.clean("  슨푸성   공원 ", "駿府城公園"));
        assertEquals("슨푸성 공원", PlaceNames.clean("\"슨푸성 공원\"", "駿府城公園"));
        assertEquals("기요미즈데라", PlaceNames.clean("기요미즈데라(清水寺)", "清水寺"), "원문을 괄호로 덧붙이면 괄호만 뗀다");
        assertEquals("기요미즈데라", PlaceNames.clean("기요미즈데라 （清水寺）", "清水寺"));
        assertEquals("호텔 아소시아 시즈오카 (본관)", PlaceNames.clean("호텔 아소시아 시즈오카 (본관)", "Hotel Associa Shizuoka"), "한글 괄호는 이름의 일부다");
        assertEquals("스타벅스 커피 LUSCA 누마즈점", PlaceNames.clean("스타벅스 커피 LUSCA 누마즈점", "Starbucks Coffee LUSCA Numazu"));

        assertNull(PlaceNames.clean("Sunpu Castle Park", "駿府城公園"), "한글이 없으면 쓰지 않는다");
        assertNull(PlaceNames.clean("슨푸城 공원", "駿府城公園"), "일본어 글자가 남아 있으면 쓰지 않는다");
        assertNull(PlaceNames.clean("슨푸성 공원\n시즈오카의 대표 공원", "駿府城公園"), "여러 줄(설명을 덧붙인 답)은 쓰지 않는다");
        assertNull(PlaceNames.clean("공원|슨푸성 공원", "駿府城公園"));
        assertNull(PlaceNames.clean("가".repeat(PlaceNames.MAX_LENGTH + 1), "駿府城公園"));
        assertNull(PlaceNames.clean("   ", "駿府城公園"));
        assertNull(PlaceNames.clean(null, "駿府城公園"));
        assertNull(PlaceNames.clean("라멘 이치란", "라멘 이치란"), "원래 이름과 같으면 따로 둘 필요가 없다");
    }

    @Test
    void shownNameFallsBackToTheOriginalName() {
        Place p = new Place();
        p.setName("駿府城公園");
        assertEquals("駿府城公園", p.shownName());
        p.setNameKo("  ");
        assertEquals("駿府城公園", p.shownName());
        p.setNameKo("슨푸성 공원");
        assertEquals("슨푸성 공원", p.shownName());
        assertEquals("駿府城公園", p.getName(), "원래 이름은 바뀌지 않는다");
    }

    @Test
    void originalNamesInsideSentencesAreReplaced() {
        Place park = place("駿府城公園", "슨푸성 공원");
        Place gate = place("駿府城公園 東御門", "슨푸성 공원 히가시고몬");
        Place gu = place("GU", "지유");                      // 두 글자 이름은 다른 낱말 속에서 잘못 바뀔 수 있어 건드리지 않는다
        Place korean = place("도키와 공원", null);

        String text = "Day 2: 시내에 '역사' 테마 장소가 부족해 '駿府城公園 東御門'을(를) 넣었습니다. 駿府城公園 근처 GUEST HOUSE 와 도키와 공원.";
        assertEquals("Day 2: 시내에 '역사' 테마 장소가 부족해 '슨푸성 공원 히가시고몬'을(를) 넣었습니다. 슨푸성 공원 근처 GUEST HOUSE 와 도키와 공원.",
                PlaceNames.localize(text, List.of(park, gate, gu, korean)), "긴 이름부터 바꾼다");
        assertEquals(text, PlaceNames.localize(text, List.of(korean)));
        assertNull(PlaceNames.localize(null, List.of(park)));
    }

    private static Place place(String name, String nameKo) {
        Place p = new Place();
        p.setName(name);
        p.setNameKo(nameKo);
        return p;
    }
}
