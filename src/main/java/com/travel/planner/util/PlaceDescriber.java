package com.travel.planner.util;

import com.travel.planner.entity.Place;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 장소의 "세부 유형"과 "한 줄 소개".
 *
 * DB 에 AI 가 채운 값(Place.subType / Place.summary)이 있으면 그것을 쓴다.
 * 아직 없으면 이름·분류·테마에서 알 수 있는 범위로만 기본 문구를 만든다.
 * 기본 문구는 "공원", "라멘 전문점"처럼 유형 수준의 말만 쓰고, 그 장소만의 사실은 지어내지 않는다.
 */
public final class PlaceDescriber {

    public static final int MAX_SUBTYPE_LENGTH = 20;
    public static final int MAX_SUMMARY_LENGTH = 120;

    private PlaceDescriber() {}

    // 관광지 이름 키워드 → 세부 유형. 위에서부터 먼저 맞는 것을 쓴다.
    // (예: "슨푸성 공원"은 성이 아니라 공원이므로 공원·정원을 성보다 먼저 본다)
    private static final String[][] ATTRACTION_TYPES = {
            {"온천", "온천", "温泉", "の湯", "노유", "onsen", "센토", "족욕", "스파"},
            {"미술관", "미술관", "美術館", "갤러리", "gallery", "art museum"},
            {"박물관", "박물관", "博物館", "museum", "기념관", "記念館", "자료관", "과학관"},
            {"수족관", "수족관", "aquarium", "아쿠아리움", "水族館"},
            {"동물원", "동물원", "zoo", "動物園"},
            {"식물원", "식물원", "botanical", "植物園"},
            {"전망대", "전망대", "展望", "observat", "타워", "tower", "스카이", "테라스", "대관람차", "로프웨이", "ropeway"},
            {"신사", "신사", "神社", "shrine", "신궁", "텐만구", "도쇼구", "하치만구", "다이샤", "진자"},
            {"사찰", "사원", "temple", "寺", "데라", "사찰"},
            {"성터", "성터", "성 유적", "castle ruins", "城跡", "城址"},
            {"정원", "정원", "庭園", "garden", "가든"},
            {"공원", "공원", "公園", "park", "파크", "광장"},
            {"성", "castle", "城"},
            {"해변", "해변", "해안", "beach", "비치", "마쓰바라", "마츠바라", "해수욕장"},
            {"호수", "호수", "lake", "湖"},
            {"폭포", "폭포", "falls", "滝"},
            {"시장", "시장", "市場", "market", "이치바"},
            {"거리", "거리", "street", "상점가", "商店街", "요코초", "골목"},
            {"동상·기념물", "동상", "像", "statue", "기념비", "monument"},
    };

    private static final String[][] SHOPPING_TYPES = {
            {"시장", "시장", "市場", "market", "이치바", "신선관"},
            {"전자제품 매장", "빅카메라", "빅 카메라", "요도바시", "bic camera", "에디온"},
            {"할인 잡화점", "돈키호테", "don quijote", "다이소", "3coins", "100엔"},
            {"드럭스토어", "드럭", "drug", "마쓰모토키요시", "마츠모토키요시"},
            {"슈퍼마켓", "슈퍼", "supermarket", "aeon", "세이유", "valor"},
            {"편의점", "편의점", "로손", "lawson", "세븐일레븐", "패밀리마트"},
            {"아울렛", "아울렛", "outlet"},
            {"백화점", "백화점", "department", "百貨店", "다이마루", "마쓰자카야", "이세탄", "다카시마야"},
            {"쇼핑몰", "몰", "mall", "플라자", "plaza", "파르코", "루미네"},
            {"상점가", "상점가", "商店街", "아케이드", "거리"},
            {"기념품점", "기념품", "souvenir", "특산", "오미야게"},
    };

    // PlaceKind.cuisineOf 가 모르는 음식 종류 (표시용으로만 쓴다)
    private static final String[][] EXTRA_CUISINES = {
            {"해산물", "해산물", "海鮮", "seafood", "카이센", "수산", "魚"},
            {"스테이크", "스테이크", "steak", "ステーキ"},
            {"샤브샤브·스키야키", "샤브", "しゃぶ", "스키야키", "すき焼"},
            {"버거", "버거", "burger"},
            {"중화요리", "中華", "중화", "chinese", "반점"},
            {"이탈리안", "이탈리안", "italian", "트라토리아", "trattoria"},
            {"프렌치", "프렌치", "french", "비스트로", "bistro"},
            {"한식", "한식", "korean", "韓国"},
            {"덮밥", "덮밥", "丼"},
            {"일본 가정식", "食堂", "쇼쿠도", "정식"},
    };

    // 세부 유형 → 유형 수준의 한 줄 설명 (그 장소만의 사실은 담지 않는다)
    private static final Map<String, String> TYPE_SUMMARIES = new LinkedHashMap<>();

    static {
        TYPE_SUMMARIES.put("온천", "온천욕을 즐기는 온천 시설");
        TYPE_SUMMARIES.put("미술관", "작품을 감상하는 미술관");
        TYPE_SUMMARIES.put("박물관", "전시를 둘러보는 박물관");
        TYPE_SUMMARIES.put("수족관", "해양 생물을 관람하는 수족관");
        TYPE_SUMMARIES.put("동물원", "동물을 관람하는 동물원");
        TYPE_SUMMARIES.put("식물원", "식물과 꽃을 둘러보는 식물원");
        TYPE_SUMMARIES.put("전망대", "전망을 즐기는 전망 명소");
        TYPE_SUMMARIES.put("신사", "일본 전통 신사");
        TYPE_SUMMARIES.put("사찰", "일본 전통 사찰");
        TYPE_SUMMARIES.put("성터", "옛 성의 자취가 남은 성터");
        TYPE_SUMMARIES.put("성", "일본 성곽 명소");
        TYPE_SUMMARIES.put("정원", "산책하며 둘러보는 정원");
        TYPE_SUMMARIES.put("공원", "산책하며 쉬어 가기 좋은 공원");
        TYPE_SUMMARIES.put("해변", "바다를 볼 수 있는 해변");
        TYPE_SUMMARIES.put("호수", "호수 풍경을 즐기는 명소");
        TYPE_SUMMARIES.put("폭포", "폭포를 볼 수 있는 자연 명소");
        TYPE_SUMMARIES.put("시장", "현지 먹거리와 특산품을 파는 시장");
        TYPE_SUMMARIES.put("거리", "상점과 먹거리가 모인 거리");
        TYPE_SUMMARIES.put("동상·기념물", "잠깐 들러 사진을 남기는 기념물");
        TYPE_SUMMARIES.put("테마파크", "하루 일정으로 즐기는 테마파크");
        TYPE_SUMMARIES.put("자연 명소", "자연 경관을 즐기는 명소");
        TYPE_SUMMARIES.put("야경 명소", "해가 진 뒤 야경을 보기 좋은 명소");
        TYPE_SUMMARIES.put("문화 명소", "역사·문화를 느낄 수 있는 명소");
        TYPE_SUMMARIES.put("서브컬쳐 명소", "애니메이션·게임 등 서브컬쳐 명소");
        TYPE_SUMMARIES.put("체험·액티비티", "직접 체험하며 즐기는 곳");
        TYPE_SUMMARIES.put("포토 스팟", "사진을 남기기 좋은 곳");
        TYPE_SUMMARIES.put("전자제품 매장", "가전·카메라 등을 파는 전자제품 매장");
        TYPE_SUMMARIES.put("할인 잡화점", "생활용품·과자·기념품을 파는 할인 잡화점");
        TYPE_SUMMARIES.put("드럭스토어", "의약품·화장품을 파는 드럭스토어");
        TYPE_SUMMARIES.put("슈퍼마켓", "식료품을 파는 슈퍼마켓");
        TYPE_SUMMARIES.put("아울렛", "브랜드 할인 매장이 모인 아울렛");
        TYPE_SUMMARIES.put("백화점", "패션·식품관이 있는 백화점");
        TYPE_SUMMARIES.put("쇼핑몰", "여러 매장이 모인 쇼핑몰");
        TYPE_SUMMARIES.put("상점가", "작은 가게가 이어진 상점가");
        TYPE_SUMMARIES.put("기념품점", "지역 특산품·기념품을 파는 가게");
    }

    /** 세부 유형. 저장된 값 우선, 없으면 이름·분류에서 추정. */
    public static String subTypeOf(Place p) {
        if (p == null) return null;
        if (p.getSubType() != null && !p.getSubType().isBlank()) return p.getSubType().trim();

        String category = p.getCategory() == null ? "" : p.getCategory();
        String rawName = p.getName() == null ? "" : p.getName().trim();
        if ("숙소".equals(category)) return "숙소";
        if ("교통".equals(category)) return rawName.contains("공항") || rawName.toLowerCase().contains("airport") ? "공항" : "교통";

        String name = rawName.toLowerCase();
        switch (PlaceKind.of(p)) {
            case RESTAURANT: {
                String cuisine = PlaceKind.cuisineOf(p.getName());
                if (cuisine != null) return cuisineLabel(cuisine);
                String extra = match(EXTRA_CUISINES, name);
                if (extra != null) return extra;
                if (name.contains("시장") || name.contains("market") || name.contains("이치바")) return "시장 식당가";
                return "식당";
            }
            case CAFE:
                return name.contains("베이커리") || name.contains("bakery") ? "베이커리 카페" : "카페";
            case BAR:
                return "이자카야";
            case THEME_PARK:
                return "테마파크";
            case SHOPPING: {
                String type = match(SHOPPING_TYPES, name);
                return type != null ? type : "쇼핑";
            }
            default: {
                String type = match(ATTRACTION_TYPES, name);
                if (type != null) return type;
                if (rawName.matches(".*[가-힣]\\s?성")) return "성";   // "하마마쓰성", "오사카 성"
                Set<String> themes = themesOf(p);
                for (String[] pair : new String[][]{{"온천", "온천"}, {"자연", "자연 명소"}, {"야경", "야경 명소"}, {"문화", "문화 명소"},
                        {"서브컬쳐", "서브컬쳐 명소"}, {"액티비티", "체험·액티비티"}, {"사진", "포토 스팟"}}) {
                    if (themes.contains(pair[0])) return pair[1];
                }
                return "관광 명소";
            }
        }
    }

    /** DB 에 AI 가 쓴 소개가 있는가 */
    public static boolean hasStoredSummary(Place p) {
        return p != null && p.getSummary() != null && !p.getSummary().isBlank();
    }

    /** 한 줄 소개. 저장된 값 우선, 없으면 유형 수준의 기본 문구. 숙소·교통은 null. */
    public static String summaryOf(Place p) {
        if (p == null) return null;
        if (hasStoredSummary(p)) return p.getSummary().trim();

        String category = p.getCategory() == null ? "" : p.getCategory();
        if ("숙소".equals(category) || "교통".equals(category)) return null;

        String subType = subTypeOf(p);
        switch (PlaceKind.of(p)) {
            case RESTAURANT:
                if ("식당".equals(subType)) return "현지 식당";
                if ("시장 식당가".equals(subType)) return "시장 안에서 식사할 수 있는 식당가";
                if ("일본 가정식".equals(subType)) return "일본 가정식을 내는 식당";
                return subType + " 전문점";
            case CAFE:
                return "베이커리 카페".equals(subType) ? "빵과 음료를 파는 베이커리 카페" : "쉬어 가기 좋은 카페";
            case BAR:
                return "술과 안주를 즐기는 이자카야(주점)";
            default: {
                String generic = TYPE_SUMMARIES.get(subType);
                if (generic != null) return generic;
                // 저장된 세부 유형(AI 가 쓴 값)은 있는데 소개만 비어 있는 경우 등
                return "쇼핑".equals(subType) ? "쇼핑을 즐기는 곳" : "관광 명소".equals(subType) ? "관광 명소" : subType;
            }
        }
    }

    /** 장소의 테마 목록(저장된 테마, 없으면 추정). 응답 표시용. */
    public static List<String> themeListOf(Place p) {
        List<String> out = new ArrayList<>();
        if (p == null) return out;
        Set<String> themes = themesOf(p);
        for (String t : ThemeVocabulary.THEMES) {
            if (themes.contains(t)) out.add(t);
        }
        return out;
    }

    /**
     * AI 가 돌려준 세부 유형을 저장 가능한 형태로 다듬는다. 쓸 수 없으면 null.
     * 줄바꿈·따옴표·구분자를 지우고, 너무 길면(문장을 넣은 경우) 버린다.
     */
    public static String cleanSubType(String raw) {
        String s = clean(raw);
        if (s == null || s.length() > MAX_SUBTYPE_LENGTH) return null;
        return s;
    }

    /** AI 가 돌려준 한 줄 소개를 다듬는다. 쓸 수 없으면 null. 길면 마지막 문장 경계에서 자른다. */
    public static String cleanSummary(String raw) {
        String s = clean(raw);
        if (s == null || s.length() < 4) return null;
        if (s.length() > MAX_SUMMARY_LENGTH) {
            String cut = s.substring(0, MAX_SUMMARY_LENGTH);
            int end = Math.max(cut.lastIndexOf('.'), Math.max(cut.lastIndexOf('。'), cut.lastIndexOf(',')));
            s = end >= MAX_SUMMARY_LENGTH / 2 ? cut.substring(0, end).trim() : cut.trim() + "…";
        }
        return s;
    }

    private static String clean(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[\\r\\n\\t|]+", " ").replaceAll("[\"“”`]", "").replaceAll("\\s{2,}", " ").trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase();
        if (lower.equals("null") || lower.equals("없음") || lower.equals("모름") || lower.equals("알 수 없음") || lower.equals("-")) return null;
        return s;
    }

    private static Set<String> themesOf(Place p) {
        Set<String> explicit = ThemeVocabulary.explicitThemes(p);
        return explicit.isEmpty() ? ThemeVocabulary.inferredThemes(p) : explicit;
    }

    private static String cuisineLabel(String cuisine) {
        switch (cuisine) {
            case "우나기": return "장어";
            case "텐동": return "덴푸라·텐동";
            case "햄버그": return "햄버그 스테이크";
            default: return cuisine;
        }
    }

    private static String match(String[][] table, String lowerName) {
        for (String[] row : table) {
            for (int i = 1; i < row.length; i++) {
                if (lowerName.contains(row[i])) return row[0];
            }
        }
        return null;
    }
}
