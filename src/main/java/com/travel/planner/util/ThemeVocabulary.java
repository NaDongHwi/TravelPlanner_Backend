package com.travel.planner.util;

import com.travel.planner.entity.Place;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테마 12종 어휘와 관련 변환을 한 곳에서 관리한다.
 * (AI 프롬프트, 인리치먼트 파서, 긴급 수집, 스코어링이 각자 다른 문자열을 쓰던 문제를 없앤다.)
 */
public final class ThemeVocabulary {

    public static final List<String> THEMES = List.of(
            "맛집", "쇼핑", "관광", "힐링", "사진", "서브컬쳐", "문화", "자연", "야경", "온천", "액티비티", "카페");

    private static final Map<String, String> ALIASES = new LinkedHashMap<>();
    private static final Map<String, String> SEARCH_QUERIES = new LinkedHashMap<>();
    private static final Map<String, String[]> NAME_KEYWORDS = new LinkedHashMap<>();

    static {
        ALIASES.put("식도락", "맛집"); ALIASES.put("음식", "맛집"); ALIASES.put("미식", "맛집"); ALIASES.put("먹방", "맛집");
        ALIASES.put("서브컬처", "서브컬쳐"); ALIASES.put("애니메이션", "서브컬쳐"); ALIASES.put("애니", "서브컬쳐"); ALIASES.put("덕질", "서브컬쳐");
        ALIASES.put("역사", "문화"); ALIASES.put("전통", "문화"); ALIASES.put("예술", "문화");
        ALIASES.put("휴식", "힐링"); ALIASES.put("휴양", "힐링");
        ALIASES.put("체험", "액티비티"); ALIASES.put("레저", "액티비티");
        ALIASES.put("커피", "카페"); ALIASES.put("디저트", "카페");
        ALIASES.put("포토", "사진"); ALIASES.put("인생샷", "사진");
        ALIASES.put("관광지", "관광"); ALIASES.put("명소", "관광"); ALIASES.put("랜드마크", "관광");

        SEARCH_QUERIES.put("맛집", "인기 맛집");
        SEARCH_QUERIES.put("쇼핑", "쇼핑 명소");
        SEARCH_QUERIES.put("관광", "관광 명소");
        SEARCH_QUERIES.put("힐링", "정원 공원 산책");
        SEARCH_QUERIES.put("사진", "포토 스팟 전망대");
        SEARCH_QUERIES.put("서브컬쳐", "애니메이션 굿즈 성지");
        SEARCH_QUERIES.put("문화", "박물관 미술관 신사 사원");
        SEARCH_QUERIES.put("자연", "자연 명소");
        SEARCH_QUERIES.put("야경", "야경 전망대");
        SEARCH_QUERIES.put("온천", "온천");
        SEARCH_QUERIES.put("액티비티", "체험 액티비티");
        SEARCH_QUERIES.put("카페", "인기 카페");

        // DB 테마가 아직 비어 있는 장소를 위한 이름 기반 추정 키워드 (인리치먼트 전 임시 판정)
        NAME_KEYWORDS.put("온천", new String[]{"온천", "温泉", "스파", "onsen", "센토", "목욕탕"});
        NAME_KEYWORDS.put("문화", new String[]{"박물관", "미술관", "신사", "사원", "신궁", "궁", "寺", "神社", "museum", "shrine", "temple", "castle", "기념관", "성터", "유적"});
        NAME_KEYWORDS.put("자연", new String[]{"공원", "정원", "폭포", "호수", "해변", "숲", "계곡", "garden", "park", "식물원", "산책로", "대나무"});
        NAME_KEYWORDS.put("야경", new String[]{"전망대", "타워", "tower", "observatory", "스카이", "야경", "일루미네이션"});
        NAME_KEYWORDS.put("사진", new String[]{"전망대", "타워", "포토", "대관람차", "도리이"});
        NAME_KEYWORDS.put("서브컬쳐", new String[]{"애니", "만화", "포켓몬", "지브리", "건담", "animate", "애니메이트", "피규어", "게임센터", "만다라케", "닌텐도", "점프샵"});
        NAME_KEYWORDS.put("액티비티", new String[]{"체험", "수족관", "유니버설", "유니버셜", "디즈니", "테마파크", "크루즈", "레고랜드", "동물원", "팀랩", "teamlab"});
        NAME_KEYWORDS.put("힐링", new String[]{"온천", "정원", "공원", "스파", "산책"});
    }

    private ThemeVocabulary() {}

    /** 사용자 입력/AI 응답 테마 한 단어를 12종 어휘로 정규화. 해당 없으면 null. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        if (THEMES.contains(t)) return t;
        if (ALIASES.containsKey(t)) return ALIASES.get(t);
        for (String theme : THEMES) {
            if (t.contains(theme)) return theme;
        }
        for (Map.Entry<String, String> e : ALIASES.entrySet()) {
            if (t.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /** "쇼핑, 서브컬쳐 / 기타" 같은 문자열에서 어휘에 있는 테마만 순서대로 추출. */
    public static List<String> normalizeList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String token : raw.split("[,/、·\\s]+")) {
            String n = normalize(token);
            if (n != null && !out.contains(n)) out.add(n);
        }
        return out;
    }

    public static List<String> normalizeAll(List<String> raws) {
        List<String> out = new ArrayList<>();
        if (raws == null) return out;
        for (String r : raws) {
            String n = normalize(r);
            if (n != null && !out.contains(n)) out.add(n);
        }
        return out;
    }

    /** 긴급 수집 시 구글 텍스트 검색에 쓸 검색어. 테마 단어를 그대로 검색하면 "사진" → 사진관이 걸린다. */
    public static String searchQueryFor(String theme) {
        String n = normalize(theme);
        return n != null ? SEARCH_QUERIES.get(n) : null;
    }

    /** DB에 저장된(AI가 분류한) 테마. */
    public static Set<String> explicitThemes(Place p) {
        return new LinkedHashSet<>(normalizeList(p.getTheme()));
    }

    /**
     * DB 테마가 비어 있을 때 카테고리·이름으로 추정한 테마.
     * 인리치먼트가 끝나기 전에도 테마 매칭이 0건이 되지 않게 하는 안전장치다.
     */
    public static Set<String> inferredThemes(Place p) {
        Set<String> out = new LinkedHashSet<>();
        String name = p.getName() == null ? "" : p.getName().toLowerCase();
        String category = p.getCategory() == null ? "" : p.getCategory();

        for (Map.Entry<String, String[]> e : NAME_KEYWORDS.entrySet()) {
            if (Arrays.stream(e.getValue()).anyMatch(k -> name.contains(k.toLowerCase()))) out.add(e.getKey());
        }
        // "오사카 성", "히메지성" 처럼 '성'으로 끝나는 관광지
        if (("관광지".equals(category) || category.isEmpty()) && name.matches(".*[가-힣]성($|\\s.*|\\(.*)")) out.add("문화");

        if ("식음".equals(category)) {
            out.add(PlaceKind.of(p) == PlaceKind.CAFE ? "카페" : "맛집");
        } else if ("쇼핑".equals(category)) {
            out.add("쇼핑");
        } else if ("테마파크".equals(category)) {
            out.add("액티비티");
        } else if ("관광지".equals(category) || category.isEmpty()) {
            out.add("관광");
        }
        return out;
    }
}
