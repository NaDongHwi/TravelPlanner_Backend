package com.travel.planner.util;

import com.travel.planner.entity.Region;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class PrefectureMapper {

    private static final Map<String, Region> PREFECTURE_MAP;

    static {
        Map<String, Region> map = new HashMap<>();

        // 1. HOKKAIDO (1개 도도부현)
        map.put("홋카이도", Region.HOKKAIDO);

        // 2. TOHOKU (6개 도도부현)
        map.put("아오모리현", Region.TOHOKU);
        map.put("이와테현", Region.TOHOKU);
        map.put("미야기현", Region.TOHOKU);
        map.put("아키타현", Region.TOHOKU);
        map.put("야마가타현", Region.TOHOKU);
        map.put("후쿠시마현", Region.TOHOKU);

        // 3. KANTO (7개 도도부현)
        map.put("이바라키현", Region.KANTO);
        map.put("도치기현", Region.KANTO);
        map.put("군마현", Region.KANTO);
        map.put("사이타마현", Region.KANTO);
        map.put("치바현", Region.KANTO);
        map.put("도쿄도", Region.KANTO);
        map.put("가나가와현", Region.KANTO);

        // 4. CHUBU (9개 도도부현)
        map.put("니가타현", Region.CHUBU);
        map.put("도야마현", Region.CHUBU);
        map.put("이시카와현", Region.CHUBU);
        map.put("후쿠이현", Region.CHUBU);
        map.put("야마나시현", Region.CHUBU);
        map.put("나가노현", Region.CHUBU);
        map.put("기후현", Region.CHUBU);
        map.put("시즈오카현", Region.CHUBU);
        map.put("아이치현", Region.CHUBU);

        // 5. KANSAI (7개 도도부현)
        map.put("미에현", Region.KANSAI);
        map.put("시가현", Region.KANSAI);
        map.put("교토부", Region.KANSAI);
        map.put("오사카부", Region.KANSAI);
        map.put("효고현", Region.KANSAI);
        map.put("나라현", Region.KANSAI);
        map.put("와카야마현", Region.KANSAI);

        // 6. CHUGOKU (5개 도도부현)
        map.put("돗토리현", Region.CHUGOKU);
        map.put("시마네현", Region.CHUGOKU);
        map.put("오카야마현", Region.CHUGOKU);
        map.put("히로시마현", Region.CHUGOKU);
        map.put("야마구치현", Region.CHUGOKU);

        // 7. SHIKOKU (4개 도도부현)
        map.put("도쿠시마현", Region.SHIKOKU);
        map.put("가가와현", Region.SHIKOKU);
        map.put("에히메현", Region.SHIKOKU);
        map.put("고치현", Region.SHIKOKU);

        // 8. KYUSHU (7개 도도부현)
        map.put("후쿠오카현", Region.KYUSHU);
        map.put("사가현", Region.KYUSHU);
        map.put("나가사키현", Region.KYUSHU);
        map.put("구마모토현", Region.KYUSHU);
        map.put("오이타현", Region.KYUSHU);
        map.put("미야자키현", Region.KYUSHU);
        map.put("가고시마현", Region.KYUSHU);

        // 9. OKINAWA (1개 도도부현)
        map.put("오키나와현", Region.OKINAWA);

        PREFECTURE_MAP = Collections.unmodifiableMap(map);
    }

    /**
     * 구글 formatted_address 내부에서 47개 도도부현 명칭을 실시간 스캔하여 정밀 권역을 반환합니다.
     * 잘못된 입력값이나 타국가 지명 유입 시 즉시 Runtime 예외를 던져 서버 적재를 엄격히 차단합니다.
     */
    public static Region getRegionFromAddress(String formattedAddress) {
        if (formattedAddress == null || formattedAddress.isEmpty()) {
            throw new IllegalArgumentException("구글 주소 값이 누락되어 유효 권역을 식별할 수 없습니다.");
        }

        String cleanAddress = formattedAddress.replace(" ", "");

        for (String prefecture : PREFECTURE_MAP.keySet()) {
            if (cleanAddress.contains(prefecture)) {
                return PREFECTURE_MAP.get(prefecture);
            }
        }

        // 47개 중 매핑 단어가 없으면 에러를 뿜으며 하위 트랜잭션을 전면 무산시킵니다.
        throw new IllegalArgumentException("일본 47개 정식 행정 권역에 매핑되지 않는 무효한 지명 주소입니다: " + formattedAddress);
    }
}