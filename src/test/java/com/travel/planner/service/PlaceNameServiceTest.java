package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 이름이 영어·일본어뿐인 장소에 "보여 줄 한국어 이름"을 붙이는 동작 테스트. 실제 AI 대신 정해 둔 답을 돌려주는 가짜를 쓴다. */
class PlaceNameServiceTest {

    /** 정해 둔 답을 돌려주고, 무엇을 물었는지 기록한다. */
    static class FakeAi extends AiService {
        final Map<String, String> answers = new HashMap<>();
        final List<String> asked = Collections.synchronizedList(new ArrayList<>());
        long delayMillis = 0;

        FakeAi() { super(null, null); }

        @Override
        public Map<String, String> translatePlaceNames(List<Place> places) {
            try { Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Map<String, String> out = new HashMap<>();
            for (Place p : places) {
                asked.add(p.getName());
                if (answers.containsKey(p.getName())) out.put(p.getPlaceId(), answers.get(p.getName()));
            }
            return out;
        }
    }

    @Test
    void onlyForeignNamesWithoutAKoreanNameAreAskedAndOnlyTheNameColumnIsSaved() throws Exception {
        FakeAi ai = new FakeAi();
        ai.answers.put("駿府城公園", "슨푸성 공원");
        ai.answers.put("Hotel Associa Shizuoka", "호텔 아소시아 시즈오카");
        ai.answers.put("Nihondaira Yume Terrace", "Nihondaira Yume Terrace");        // 한글이 없는 답: 버린다
        ai.answers.put("久能山東照宮", "구노잔 도쇼구(久能山東照宮)");                  // 원문을 덧붙인 답: 괄호만 뗀다
        Map<Long, String> saved = new HashMap<>();
        List<String> otherCalls = new ArrayList<>();
        PlaceNameService service = service(ai, saved, otherCalls);

        Place park = place(1L, "P1", "駿府城公園", "관광지");
        Place hotel = place(2L, "P2", "Hotel Associa Shizuoka", "숙소");
        Place korean = place(3L, "P3", "도키와 공원", "관광지");
        Place done = place(4L, "P4", "三保の松原", "관광지");
        done.setNameKo("미호노마쓰바라");
        Place rejected = place(5L, "P5", "Nihondaira Yume Terrace", "관광지");
        Place shrine = place(6L, "P6", "久能山東照宮", "관광지");
        Place silent = place(7L, "P7", "Unanswered Cafe", "식음");
        Place unsaved = place(null, "P8", "駿府城公園", "관광지");                      // 아직 DB 에 없는 장소: 값만 넣고 저장하지 않는다
        unsaved.setPlaceId("P8");
        Place free = place(9L, "DUMMY_FREE", "Free Time", "자유시간");

        int filled = service.fillMissing(List.of(park, hotel, korean, done, rejected, shrine, silent, park, unsaved, free));

        assertEquals(4, filled);
        assertEquals("슨푸성 공원", park.getNameKo());
        assertEquals("駿府城公園", park.getName(), "원래 이름은 그대로 둔다");
        assertEquals("호텔 아소시아 시즈오카", hotel.getNameKo());
        assertEquals("구노잔 도쇼구", shrine.getNameKo());
        assertEquals("슨푸성 공원", unsaved.getNameKo());
        assertNull(korean.getNameKo(), "이미 한국어인 이름은 묻지 않는다");
        assertEquals("미호노마쓰바라", done.getNameKo());
        assertNull(rejected.getNameKo(), "쓸 수 없는 답은 버리고 원래 이름을 보여 준다");
        assertNull(silent.getNameKo());
        assertNull(free.getNameKo());
        assertEquals(List.of("駿府城公園", "Hotel Associa Shizuoka", "Nihondaira Yume Terrace", "久能山東照宮", "Unanswered Cafe", "駿府城公園"), ai.asked,
                "한국어 이름이 필요한데 없는 곳만, 장소마다 한 번씩 묻는다");
        assertEquals(Map.of(1L, "슨푸성 공원", 2L, "호텔 아소시아 시즈오카", 6L, "구노잔 도쇼구"), saved, "DB 에 있는 장소의 이름 칸만 고친다");
        assertTrue(otherCalls.isEmpty(), "장소를 통째로 저장하지 않는다: " + otherCalls);

        // 다음 일정·문서: 이미 붙인 이름은 다시 묻지 않는다
        ai.asked.clear();
        assertEquals(0, service.fillMissing(List.of(park, hotel, done, shrine, korean)));
        assertTrue(ai.asked.isEmpty());
    }

    @Test
    void slowAiDoesNotHoldTheResponseAndTheLateAnswerIsStillSaved() throws Exception {
        FakeAi ai = new FakeAi();
        ai.delayMillis = 2500;
        ai.answers.put("駿府城公園", "슨푸성 공원");
        Map<Long, String> saved = Collections.synchronizedMap(new HashMap<>());
        PlaceNameService service = service(ai, saved, new ArrayList<>());
        set(service, "timeoutSeconds", 1);
        Place park = place(1L, "P1", "駿府城公園", "관광지");

        long started = System.currentTimeMillis();
        int filled = service.fillMissing(List.of(park));
        long waited = System.currentTimeMillis() - started;

        assertEquals(0, filled);
        assertTrue(waited < 2200, "제한 시간(1초)만 기다려야 한다. 실제 " + waited + "ms");
        assertEquals("駿府城公園", park.shownName(), "제때 오지 않으면 원래 이름으로 응답한다");

        // 늦게 온 답은 버리지 않고 DB 에 저장한다 (다음 일정부터 쓰인다).
        // 이미 응답을 만들고 있는 Place 객체는 건드리지 않는다: 한 응답 안에서 이름이 섞이지 않게 하기 위해서다.
        for (int i = 0; i < 40 && saved.isEmpty(); i++) Thread.sleep(100);
        assertEquals(Map.of(1L, "슨푸성 공원"), saved);
        assertNull(park.getNameKo());
        assertEquals("駿府城公園", park.shownName());
    }

    @Test
    void canBeTurnedOffAndAiFailureKeepsTheOriginalNames() throws Exception {
        FakeAi ai = new FakeAi();
        ai.answers.put("駿府城公園", "슨푸성 공원");
        PlaceNameService off = service(ai, new HashMap<>(), new ArrayList<>());
        set(off, "enabled", false);
        Place park = place(1L, "P1", "駿府城公園", "관광지");
        assertEquals(0, off.fillMissing(List.of(park)));
        assertTrue(ai.asked.isEmpty());

        AiService failing = new AiService(null, null) {
            @Override
            public Map<String, String> translatePlaceNames(List<Place> places) {
                throw new IllegalStateException("AI 서버 오류");
            }
        };
        assertEquals(0, service(failing, new HashMap<>(), new ArrayList<>()).fillMissing(List.of(park)), "AI 가 실패해도 예외를 밖으로 내보내지 않는다");
        assertEquals("駿府城公園", park.shownName());
    }

    // ---------------------------------------------------------------- 준비

    private static PlaceNameService service(AiService ai, Map<Long, String> saved, List<String> otherCalls) {
        PlaceRepository repository = (PlaceRepository) Proxy.newProxyInstance(PlaceNameServiceTest.class.getClassLoader(),
                new Class<?>[]{PlaceRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("updateNameKo")) {
                        saved.put((Long) args[0], (String) args[1]);
                        return 1;
                    }
                    synchronized (otherCalls) { otherCalls.add(method.getName()); }
                    return null;
                });
        return new PlaceNameService(ai, repository);
    }

    private static Place place(Long id, String placeId, String name, String category) {
        Place p = new Place();
        p.setId(id);
        p.setPlaceId(placeId);
        p.setName(name);
        p.setCategory(category);
        return p;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = PlaceNameService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
