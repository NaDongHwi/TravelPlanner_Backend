package com.travel.planner.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 일본 주요 공항 목록.
 *
 * 이전에는 "이름에 '공항'이 들어간 DB 장소"를 공항으로 썼는데, 그러면
 *  - 공항이 DB 에 없으면 앵커가 통째로 빠지고,
 *  - inCity 가 비면 모든 공항이 가산점을 받고,
 *  - 출국 도시(outCity)는 아예 쓰이지 않았다.
 * 그래서 입·출국 도시 문자열에서 공항을 직접 결정한다.
 * 좌표는 터미널 부근 근사값이며, DB 에 같은 공항의 구글 장소가 있으면 서비스 계층에서 그쪽 좌표로 바꿔 쓴다.
 */
public final class AirportDirectory {

    public static final String PLACE_ID_PREFIX = "AIRPORT_";

    public static final class Airport {
        public final String code;
        public final String name;
        public final double latitude;
        public final double longitude;
        /** 공항 고유 이름(이 단어가 입력에 있으면 그 공항으로 확정) */
        public final String[] ownNames;
        /** 이 공항을 기본으로 쓰는 도시 이름 */
        public final String[] cities;

        Airport(String code, String name, double latitude, double longitude, String[] ownNames, String[] cities) {
            this.code = code;
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
            this.ownNames = ownNames;
            this.cities = cities;
        }

        public String placeId() {
            return PLACE_ID_PREFIX + code;
        }
    }

    public static final class Resolution {
        public final Airport airport;
        /** 도시 이름만으로 골라서 다른 공항일 수도 있는 경우 true (예: 도쿄 → 나리타/하네다) */
        public final boolean ambiguous;

        Resolution(Airport airport, boolean ambiguous) {
            this.airport = airport;
            this.ambiguous = ambiguous;
        }
    }

    private static final List<Airport> AIRPORTS = new ArrayList<>();

    private static void add(String code, String name, double lat, double lng, String[] ownNames, String... cities) {
        AIRPORTS.add(new Airport(code, name, lat, lng, ownNames, cities));
    }

    private static String[] n(String... names) {
        return names;
    }

    static {
        // 도쿄는 공항이 둘이라 도시 이름만 오면 더 먼 나리타로 잡는다(이동 시간을 넉넉하게 보는 쪽이 안전).
        add("NRT", "나리타 국제공항", 35.7653, 140.3856, n("나리타", "narita"), "도쿄", "東京", "tokyo", "치바", "지바");
        add("HND", "도쿄 국제공항(하네다)", 35.5533, 139.7811, n("하네다", "haneda"), "요코하마", "가마쿠라", "하코네");
        add("KIX", "간사이 국제공항", 34.4342, 135.2328, n("간사이", "칸사이", "kansai"), "오사카", "大阪", "osaka", "교토", "京都", "kyoto", "나라", "고베", "와카야마");
        add("ITM", "오사카 국제공항(이타미)", 34.7844, 135.4392, n("이타미", "itami"));
        add("UKB", "고베 공항", 34.6328, 135.2239, n("고베공항", "고베 공항"));
        add("NGO", "주부 센트레아 국제공항", 34.8583, 136.8053, n("센트레아", "주부국제", "주부 국제", "centrair"), "나고야", "名古屋", "nagoya", "다카야마", "시라카와고");
        add("FUK", "후쿠오카 공항", 33.5844, 130.4517, n("후쿠오카공항", "후쿠오카 공항"), "후쿠오카", "福岡", "fukuoka", "유후인", "다자이후");
        add("KKJ", "기타큐슈 공항", 33.8456, 131.0350, n("기타큐슈공항", "기타큐슈 공항"), "기타큐슈", "고쿠라", "시모노세키");
        add("CTS", "신치토세 공항", 42.7753, 141.6925, n("신치토세", "치토세", "chitose"), "삿포로", "札幌", "sapporo", "오타루", "홋카이도", "노보리베츠", "후라노");
        add("HKD", "하코다테 공항", 41.7700, 140.8219, n("하코다테공항", "하코다테 공항"), "하코다테");
        add("AKJ", "아사히카와 공항", 43.6708, 142.4475, n("아사히카와공항", "아사히카와 공항"), "아사히카와", "비에이");
        add("OKA", "나하 공항", 26.1958, 127.6458, n("나하공항", "나하 공항"), "오키나와", "沖縄", "okinawa", "나하");
        add("ISG", "신이시가키 공항", 24.3964, 124.2450, n("이시가키공항", "이시가키 공항"), "이시가키");
        add("MMY", "미야코 공항", 24.7828, 125.2950, n("미야코공항", "미야코 공항"), "미야코지마", "미야코");
        add("SDJ", "센다이 공항", 38.1397, 140.9169, n("센다이공항", "센다이 공항"), "센다이", "마쓰시마");
        add("AOJ", "아오모리 공항", 40.7347, 140.6908, n("아오모리공항", "아오모리 공항"), "아오모리");
        add("KIJ", "니가타 공항", 37.9558, 139.1211, n("니가타공항", "니가타 공항"), "니가타");
        add("KMQ", "고마쓰 공항", 36.3942, 136.4075, n("고마쓰", "고마츠", "komatsu"), "가나자와");
        add("TOY", "도야마 공항", 36.6483, 137.1875, n("도야마공항", "도야마 공항"), "도야마");
        add("FSZ", "시즈오카 공항", 34.7961, 138.1894, n("시즈오카공항", "시즈오카 공항"), "시즈오카", "하마마쓰");
        add("IBR", "이바라키 공항", 36.1811, 140.4147, n("이바라키공항", "이바라키 공항"), "이바라키", "미토");
        add("HIJ", "히로시마 공항", 34.4361, 132.9194, n("히로시마공항", "히로시마 공항"), "히로시마", "미야지마");
        add("OKJ", "오카야마 공항", 34.7569, 133.8553, n("오카야마공항", "오카야마 공항"), "오카야마", "구라시키");
        add("YGJ", "요나고 공항", 35.4922, 133.2364, n("요나고", "yonago"), "돗토리", "마쓰에");
        add("TAK", "다카마쓰 공항", 34.2142, 134.0156, n("다카마쓰공항", "다카마쓰 공항"), "다카마쓰", "다카마츠", "나오시마");
        add("MYJ", "마쓰야마 공항", 33.8272, 132.6997, n("마쓰야마공항", "마쓰야마 공항"), "마쓰야마", "마츠야마");
        add("KMJ", "구마모토 공항", 32.8372, 130.8550, n("구마모토공항", "구마모토 공항"), "구마모토", "아소");
        add("OIT", "오이타 공항", 33.4794, 131.7372, n("오이타공항", "오이타 공항"), "오이타", "벳푸");
        add("NGS", "나가사키 공항", 32.9169, 129.9136, n("나가사키공항", "나가사키 공항"), "나가사키");
        add("HSG", "사가 공항", 33.1497, 130.3022, n("사가공항", "사가 공항"), "사가");
        add("KMI", "미야자키 공항", 31.8772, 131.4486, n("미야자키공항", "미야자키 공항"), "미야자키");
        add("KOJ", "가고시마 공항", 31.8033, 130.7194, n("가고시마공항", "가고시마 공항"), "가고시마");
    }

    private AirportDirectory() {}

    public static List<Airport> all() {
        return AIRPORTS;
    }

    /**
     * 입·출국 도시 문자열에서 공항을 찾는다.
     * @param cityCenter 도시 중심 좌표(없으면 null). 이름으로 못 찾을 때 가장 가까운 공항을 고르는 데 쓴다.
     */
    public static Resolution resolve(String cityOrAirport, double[] cityCenter) {
        if (cityOrAirport != null && !cityOrAirport.isBlank()) {
            String text = cityOrAirport.trim().toLowerCase();

            // IATA 코드는 전체가 일치할 때만 인정한다 ("okayama" 안의 "oka" 같은 오인 방지).
            for (Airport a : AIRPORTS) {
                if (text.equals(a.code.toLowerCase())) return new Resolution(a, false);
            }
            for (Airport a : AIRPORTS) {
                for (String own : a.ownNames) {
                    if (text.contains(own)) return new Resolution(a, false);
                }
            }
            for (Airport a : AIRPORTS) {
                for (String city : a.cities) {
                    if (text.contains(city)) {
                        boolean ambiguous = "NRT".equals(a.code);
                        return new Resolution(a, ambiguous);
                    }
                }
            }
        }

        if (cityCenter != null) {
            Airport nearest = null;
            double best = Double.MAX_VALUE;
            for (Airport a : AIRPORTS) {
                double d = DistanceUtil.calculateDistance(cityCenter[0], cityCenter[1], a.latitude, a.longitude);
                if (d < best) {
                    best = d;
                    nearest = a;
                }
            }
            if (nearest != null && best <= 150.0) return new Resolution(nearest, true);
        }
        return null;
    }
}
