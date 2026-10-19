package com.travel.planner.util;

import com.travel.planner.entity.Region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 지역별 장소 수집(/admin/collect-region)에서 도는 도시 목록.
 *
 * - name: 장소 DB 에 저장되는 도시 이름. 이미 DB 에 있는 도시는 DB 표기 그대로 적었다(유후시, 비에이조 등).
 * - query: 구글 지오코딩에 넣는 이름. 이름이 겹치는 곳(구사쓰: 군마현·시가현)이나 관광지 이름과 행정 구역 이름이 다른 곳
 *   (시라카와고 → 시라카와촌, 가쿠노다테 → 센보쿠시)만 따로 적고, 나머지는 name 을 그대로 쓴다.
 * - major: 큰 관광 도시. 지금의 /collect-places 와 같이 검색어 16개 × 격자 5곳으로 모은다. 아니면 검색어 8개 × 도심 1곳.
 *
 * 지역은 한국인 방문이 많은 순서로 돈다 (PRIORITY).
 */
public final class CollectRegions {

    private CollectRegions() {}

    public static final class City {
        public final Region region;
        public final String name;
        public final String query;
        public final boolean major;

        City(Region region, String name, String query, boolean major) {
            this.region = region;
            this.name = name;
            this.query = query == null ? name : query;
            this.major = major;
        }
    }

    /** "전체"로 돌 때의 지역 순서 */
    public static final List<Region> PRIORITY = List.of(
            Region.KANSAI, Region.KANTO, Region.KYUSHU, Region.HOKKAIDO, Region.OKINAWA,
            Region.CHUBU, Region.CHUGOKU, Region.SHIKOKU, Region.TOHOKU);

    private static final Map<Region, List<City>> BY_REGION = new EnumMap<>(Region.class);

    private static void big(Region r, String name) { add(r, name, null, true); }
    private static void town(Region r, String name) { add(r, name, null, false); }
    private static void town(Region r, String name, String query) { add(r, name, query, false); }

    private static void add(Region r, String name, String query, boolean major) {
        BY_REGION.computeIfAbsent(r, k -> new ArrayList<>()).add(new City(r, name, query, major));
    }

    static {
        Region r = Region.KANSAI;
        big(r, "오사카"); big(r, "교토"); big(r, "고베"); big(r, "나라");
        town(r, "히메지"); town(r, "와카야마");
        town(r, "우지", "우지시 교토부");
        town(r, "시라하마", "시라하마정 와카야마현");
        town(r, "다나베", "다나베시 와카야마현");            // 구마노코도
        town(r, "오쓰", "오쓰시 시가현");
        town(r, "히코네", "히코네시 시가현");
        town(r, "나가하마", "나가하마시 시가현");
        town(r, "미야즈", "미야즈시 교토부");                // 아마노하시다테
        town(r, "도요오카", "도요오카시 효고현");            // 기노사키 온천
        town(r, "이세", "이세시 미에현");
        town(r, "도바", "도바시 미에현");
        town(r, "아스카", "아스카촌 나라현");
        town(r, "요시노", "요시노정 나라현");
        town(r, "사카이", "사카이시 오사카부");
        town(r, "아와지", "아와지시 효고현");

        r = Region.KANTO;
        big(r, "도쿄"); big(r, "요코하마");
        town(r, "가마쿠라"); town(r, "하코네"); town(r, "에노시마"); town(r, "닛코");
        town(r, "가와고에", "가와고에시 사이타마현");
        town(r, "지치부", "지치부시 사이타마현");
        town(r, "사이타마", "사이타마시");
        town(r, "구사쓰", "구사쓰정 군마현");                // 시가현 구사쓰시와 겹친다
        town(r, "미나카미", "미나카미정 군마현");
        town(r, "시부카와", "시부카와시 군마현");            // 이카호 온천
        town(r, "다카사키", "다카사키시 군마현");
        town(r, "나리타", "나리타시 지바현");
        town(r, "지바", "지바시");
        town(r, "가모가와", "가모가와시 지바현");
        town(r, "가와사키", "가와사키시 가나가와현");
        town(r, "요코스카", "요코스카시 가나가와현");
        town(r, "오다와라", "오다와라시 가나가와현");
        town(r, "미토", "미토시 이바라키현");
        town(r, "우쓰노미야", "우쓰노미야시 도치기현");
        town(r, "나스", "나스정 도치기현");

        r = Region.KYUSHU;
        big(r, "후쿠오카"); big(r, "나가사키"); big(r, "구마모토"); big(r, "가고시마");
        town(r, "기타큐슈"); town(r, "벳푸"); town(r, "유후시");
        town(r, "다자이후", "다자이후시 후쿠오카현");
        town(r, "이토시마", "이토시마시 후쿠오카현");
        town(r, "야나가와", "야나가와시 후쿠오카현");
        town(r, "사세보", "사세보시 나가사키현");
        town(r, "시마바라", "시마바라시 나가사키현");
        town(r, "운젠", "운젠시 나가사키현");
        town(r, "아소", "아소시 구마모토현");
        town(r, "아마쿠사", "아마쿠사시 구마모토현");
        town(r, "오이타", "오이타시");
        town(r, "미야자키", "미야자키시");
        town(r, "다카치호", "다카치호정 미야자키현");
        town(r, "니치난", "니치난시 미야자키현");
        town(r, "기리시마", "기리시마시 가고시마현");
        town(r, "이부스키", "이부스키시 가고시마현");
        town(r, "야쿠시마", "야쿠시마정 가고시마현");
        town(r, "사가", "사가시");
        town(r, "가라쓰", "가라쓰시 사가현");
        town(r, "다케오", "다케오시 사가현");
        town(r, "우레시노", "우레시노시 사가현");

        r = Region.HOKKAIDO;
        big(r, "삿포로"); big(r, "하코다테");
        town(r, "오타루"); town(r, "후라노"); town(r, "비에이조"); town(r, "노보리베쓰"); town(r, "아사히카와");
        town(r, "도야코", "도야코정 홋카이도");
        town(r, "니세코", "니세코정 홋카이도");
        town(r, "지토세", "지토세시 홋카이도");
        town(r, "구시로", "구시로시 홋카이도");
        town(r, "오비히로", "오비히로시 홋카이도");
        town(r, "아바시리", "아바시리시 홋카이도");
        town(r, "샤리", "샤리정 홋카이도");                  // 시레토코
        town(r, "왓카나이", "왓카나이시 홋카이도");

        r = Region.OKINAWA;
        big(r, "오키나와");
        town(r, "온나", "온나촌 오키나와현");
        town(r, "모토부", "모토부정 오키나와현");            // 추라우미 수족관
        town(r, "나고", "나고시 오키나와현");
        town(r, "차탄", "차탄정 오키나와현");
        town(r, "요미탄", "요미탄촌 오키나와현");
        town(r, "난조", "난조시 오키나와현");
        town(r, "이시가키", "이시가키시 오키나와현");
        town(r, "미야코지마", "미야코지마시 오키나와현");

        r = Region.CHUBU;
        big(r, "나고야"); big(r, "가나자와"); big(r, "시즈오카");
        town(r, "하마마쓰"); town(r, "누마즈"); town(r, "아타미"); town(r, "후지노미야"); town(r, "고텐바"); town(r, "슈젠지");
        town(r, "이토", "이토시 시즈오카현");
        town(r, "시모다", "시모다시 시즈오카현");
        town(r, "가와구치코", "후지카와구치코정 야마나시현");
        town(r, "고후", "고후시 야마나시현");
        town(r, "다카야마", "다카야마시 기후현");
        town(r, "시라카와고", "시라카와촌 기후현");
        town(r, "게로", "게로시 기후현");
        town(r, "기후", "기후시");
        town(r, "이누야마", "이누야마시 아이치현");
        town(r, "마쓰모토", "마쓰모토시 나가노현");
        town(r, "가루이자와", "가루이자와정 나가노현");
        town(r, "나가노", "나가노시");
        town(r, "하쿠바", "하쿠바촌 나가노현");
        town(r, "도야마", "도야마시");
        town(r, "다테야마", "다테야마정 도야마현");
        town(r, "니가타", "니가타시");
        town(r, "유자와", "유자와정 니가타현");              // 에치고유자와
        town(r, "사도", "사도시 니가타현");
        town(r, "후쿠이", "후쿠이시");
        town(r, "가가", "가가시 이시카와현");

        r = Region.CHUGOKU;
        big(r, "히로시마");
        town(r, "오카야마");
        town(r, "구라시키", "구라시키시 오카야마현");
        town(r, "미야지마", "하쓰카이치시 히로시마현");      // 이쓰쿠시마 신사
        town(r, "오노미치", "오노미치시 히로시마현");
        town(r, "구레", "구레시 히로시마현");
        town(r, "이와쿠니", "이와쿠니시 야마구치현");
        town(r, "하기", "하기시 야마구치현");
        town(r, "시모노세키", "시모노세키시 야마구치현");
        town(r, "돗토리");
        town(r, "요나고", "요나고시 돗토리현");
        town(r, "마쓰에");
        town(r, "이즈모", "이즈모시 시마네현");
        town(r, "쓰와노", "쓰와노정 시마네현");

        r = Region.SHIKOKU;
        town(r, "다카마쓰"); town(r, "마쓰야마");
        town(r, "고치", "고치시");
        town(r, "도쿠시마", "도쿠시마시");
        town(r, "나루토", "나루토시 도쿠시마현");
        town(r, "고토히라", "고토히라정 가가와현");
        town(r, "나오시마", "나오시마정 가가와현");
        town(r, "쇼도시마", "쇼도시마정 가가와현");
        town(r, "미요시", "미요시시 도쿠시마현");            // 이야 계곡
        town(r, "우와지마", "우와지마시 에히메현");
        town(r, "이마바리", "이마바리시 에히메현");
        town(r, "시만토", "시만토시 고치현");

        r = Region.TOHOKU;
        big(r, "센다이");
        town(r, "마쓰시마", "마쓰시마정 미야기현");
        town(r, "아오모리");
        town(r, "히로사키", "히로사키시 아오모리현");
        town(r, "하치노헤", "하치노헤시 아오모리현");
        town(r, "도와다", "도와다시 아오모리현");
        town(r, "모리오카", "모리오카시 이와테현");
        town(r, "히라이즈미", "히라이즈미정 이와테현");
        town(r, "아키타", "아키타시");
        town(r, "가쿠노다테", "센보쿠시 아키타현");
        town(r, "야마가타", "야마가타시");
        town(r, "오바나자와", "오바나자와시 야마가타현");    // 긴잔 온천
        town(r, "아이즈와카마쓰", "아이즈와카마쓰시 후쿠시마현");
        town(r, "후쿠시마", "후쿠시마시");
    }

    public static List<City> of(Region region) {
        return Collections.unmodifiableList(BY_REGION.getOrDefault(region, List.of()));
    }

    public static List<City> all() {
        List<City> out = new ArrayList<>();
        for (Region r : PRIORITY) out.addAll(of(r));
        return out;
    }

    /**
     * "KANSAI", "kansai", "간사이", "간사이 지방" → 그 지역 하나, "ALL"/"전체" → 우선순위대로 전부.
     * 여러 지역은 쉼표로 ("KANSAI,KANTO"). 모르는 이름이면 IllegalArgumentException.
     */
    public static List<Region> parse(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("지역을 적어 주세요. 예: KANSAI, 간사이, ALL");
        List<Region> out = new ArrayList<>();
        for (String part : input.split("[,，]")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.equalsIgnoreCase("ALL") || p.equals("전체")) {
                for (Region r : PRIORITY) if (!out.contains(r)) out.add(r);
                continue;
            }
            Region found = null;
            for (Region r : Region.values()) {
                if (r.name().equalsIgnoreCase(p) || r.getDescription().equals(p) || r.getDescription().replace(" 지방", "").equals(p)) {
                    found = r;
                    break;
                }
            }
            if (found == null) {
                throw new IllegalArgumentException("모르는 지역입니다: " + p + " (쓸 수 있는 값: "
                        + String.join(", ", PRIORITY.stream().map(x -> x.name().toUpperCase(Locale.ROOT)).toList()) + ", ALL)");
            }
            if (!out.contains(found)) out.add(found);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("지역을 적어 주세요. 예: KANSAI, 간사이, ALL");
        return out;
    }
}
