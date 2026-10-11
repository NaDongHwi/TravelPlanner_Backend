package com.travel.planner.transit;

/**
 * 철도 종류별 "거리 → 요금·시간" 추정 모델.
 *
 * 일본 철도는 운영사마다 요금표가 다르고 대부분 공개 API 가 없다. 그래서 여기서는 정확한 요금이 아니라
 * 종류별 대표 요금표로 계산한 추정값을 낸다.
 *
 *  - JR: 혼슈 3사 간선 보통운임표(2025년까지 쓰이던 표)를 그대로 옮긴 것. 도시권 특정 구간 할인·이후 인상분은 반영하지 않는다.
 *  - 신칸센: JR 보통운임 + 자유석 특급요금(거리 구간별 대략값)
 *
 * 운영사가 공개한 요금표·역 쌍 요금·신칸센 영업킬로를 등록해 두면 {@link FareBook} 이 이 추정값 대신 그 값을 쓴다.
 * 여기의 표는 등록된 자료가 없는 운영사에만 쓰인다.
 *  - 지하철: 도쿄메트로·도에이·오사카메트로는 회사별 구간 요금표, 그 외는 대표값
 *  - 사철·노면전차 등: 여러 운영사의 요금표를 참고한 대표값
 *
 * 값을 바꾸려면 이 파일의 표만 고치면 된다.
 */
public final class RailFareModel {

    private RailFareModel() {}

    // ---------------------------------------------------------------- 종류 판정

    /**
     * 국토수치정보의 코드로 종류를 정한다.
     * @param railClass    N02_001 철도 구분 (11 보통철도JR, 12 보통철도, 13 강삭철도, 14~17 모노레일·안내궤조, 21 궤도, 22~25 궤도법 모노레일 등)
     * @param operatorType N02_002 사업자 종별 (1 신칸센, 2 JR 재래선, 3 공영, 4 민영, 5 제3섹터)
     */
    public static RailCategory categoryOf(String railClass, String operatorType, String operator) {
        String rc = railClass == null ? "" : railClass.trim();
        String ot = operatorType == null ? "" : operatorType.trim();
        String op = operator == null ? "" : operator;

        if (ot.equals("1")) return RailCategory.SHINKANSEN;
        if (ot.equals("2") || rc.equals("11")) return RailCategory.JR;
        // 오사카메트로(법적으로는 궤도)처럼 코드만으로는 지하철인지 알 수 없는 곳은 이름으로 먼저 본다
        if (op.contains("地下鉄") || op.contains("メトロ") || op.contains("高速電気軌道")) return RailCategory.SUBWAY;
        if (rc.equals("13")) return RailCategory.CABLE;
        if (rc.equals("21")) return RailCategory.TRAM;
        if (ot.equals("3")) return RailCategory.SUBWAY;            // 공영 철도: 시영 지하철
        if (rc.equals("14") || rc.equals("15") || rc.equals("16") || rc.equals("17")
                || rc.equals("22") || rc.equals("23") || rc.equals("24") || rc.equals("25")) return RailCategory.GUIDED;
        if (ot.equals("5")) return RailCategory.THIRD_SECTOR;
        return RailCategory.PRIVATE;
    }

    /** 요금을 합산하는 단위. JR 은 회사가 달라도 거리를 이어서 계산하고, 그 외에는 운영사가 바뀌면 요금을 새로 낸다. */
    public static String fareGroup(RailCategory category, String operator) {
        return category == RailCategory.JR || category == RailCategory.SHINKANSEN ? "JR" : operator;
    }

    // ---------------------------------------------------------------- 시간

    /** 역 사이를 달리는 평균 속도(km/h). 정차 시간은 따로 더한다. */
    public static double speedKmh(RailCategory category) {
        switch (category) {
            case SHINKANSEN: return 240;
            case JR: return 70;
            case SUBWAY: return 45;
            case PRIVATE: return 60;
            case THIRD_SECTOR: return 55;
            case TRAM: return 18;
            case GUIDED: return 40;
            default: return 12;
        }
    }

    /** 역 하나에 설 때마다 더하는 시간(분) */
    public static double dwellMinutes(RailCategory category) {
        switch (category) {
            case SHINKANSEN: return 2.0;
            case JR: return 0.7;
            case THIRD_SECTOR: return 0.8;
            case TRAM: return 0.3;
            case GUIDED:
            case CABLE: return 0.5;
            default: return 0.6;
        }
    }

    /** 역에 도착해 열차를 탈 때까지(개찰·승강장 이동·대기) 걸리는 시간(분) */
    public static int boardingMinutes(RailCategory category) {
        return category == RailCategory.SHINKANSEN ? 12 : 5;
    }

    public static double rideMinutes(RailCategory category, double km) {
        return km / speedKmh(category) * 60.0 + dwellMinutes(category);
    }

    // ---------------------------------------------------------------- 요금

    // {이 거리(km)까지, 요금(엔)}. 거리는 소수점 올림한 정수 km 로 찾는다.
    private static final int[][] JR_FARES = {
            {3, 150}, {6, 190}, {10, 200}, {15, 240}, {20, 330}, {25, 420}, {30, 510}, {35, 590}, {40, 680}, {45, 770},
            {50, 860}, {60, 990}, {70, 1170}, {80, 1340}, {90, 1520}, {100, 1690}, {120, 1980}, {140, 2310}, {160, 2640},
            {180, 3080}, {200, 3410}, {220, 3740}, {240, 4070}, {260, 4510}, {280, 4840}, {300, 5170}, {320, 5500},
            {340, 5720}, {360, 6050}, {380, 6380}, {400, 6600}, {420, 6930}, {440, 7150}, {460, 7480}, {480, 7700},
            {500, 8030}, {520, 8360}, {540, 8580}, {560, 8910}, {580, 9130}, {600, 9460}, {640, 9790}, {680, 10010},
            {720, 10340}, {760, 10670}, {800, 11000}, {840, 11330}, {880, 11550}, {920, 11880}, {960, 12210}, {1000, 12540}};

    // 도카이도·산요 신칸센의 자유석 특급요금을 거리 구간으로 정리한 것 (도쿄·신오사카 출발 요금에서 운임을 뺀 값).
    // 실제 특급요금은 역 쌍마다 정해져 있어 이 표와 다를 수 있고, 도호쿠·호쿠리쿠 등 다른 신칸센은 금액 체계가 다르다 → 추정값으로만 쓴다.
    private static final int[][] SHINKANSEN_SURCHARGE = {
            {50, 870}, {100, 1760}, {200, 2530}, {300, 3400}, {400, 4180}, {500, 4620}, {600, 4960}, {700, 5390},
            {800, 5930}, {900, 6500}, {1000, 7040}, {1200, 8140}};

    private static final int[][] SUBWAY_FARES = {{3, 190}, {7, 230}, {13, 280}, {19, 330}, {27, 370}};

    private static final int[][] PRIVATE_FARES = {
            {4, 170}, {8, 210}, {12, 250}, {16, 290}, {20, 330}, {25, 370}, {30, 410}, {40, 500}, {50, 580}, {60, 670},
            {80, 820}, {100, 980}};

    // 이용자가 많은 지하철 3사는 회사별 구간 요금표를 쓴다 (2023년 개정 요금 기준)
    private static final int[][] TOKYO_METRO_FARES = {{6, 180}, {11, 210}, {19, 260}, {27, 300}, {40, 330}};
    private static final int[][] TOEI_SUBWAY_FARES = {{4, 180}, {9, 220}, {15, 280}, {21, 330}, {27, 380}, {46, 430}};
    private static final int[][] OSAKA_METRO_FARES = {{3, 190}, {7, 240}, {13, 290}, {19, 340}, {40, 390}};

    /** 운영사별 요금표가 있으면 그것을, 없으면 종류별 대표 요금표를 쓴다. */
    public static int fare(RailCategory category, String operator, double km) {
        if (category == RailCategory.SUBWAY && operator != null) {
            int[][] table = operator.equals("東京地下鉄") ? TOKYO_METRO_FARES
                    : operator.equals("東京都") ? TOEI_SUBWAY_FARES
                    : operator.equals("大阪市高速電気軌道") ? OSAKA_METRO_FARES : null;
            if (table != null) {
                int fare = lookup(table, km);
                return fare > 0 ? fare : table[table.length - 1][1];
            }
        }
        return fare(category, km);
    }

    private static int lookup(int[][] table, double km) {
        int whole = Math.max(1, (int) Math.ceil(km - 1e-9));
        for (int[] band : table) {
            if (whole <= band[0]) return band[1];
        }
        return -1;
    }

    /** JR 보통운임(성인, 엔). 신칸센을 타도 기본 운임은 이 표를 쓴다. */
    public static int jrFare(double km) {
        int fare = lookup(JR_FARES, km);
        if (fare > 0) return fare;
        return roundTo10(12540 + (km - 1000) * 7.8);   // 1,000km 초과분은 km 당 약 7.8엔
    }

    /** 신칸센 자유석 특급요금(성인, 엔). 지정석·노조미 가산은 포함하지 않는다. */
    public static int shinkansenSurcharge(double km) {
        if (km <= 0) return 0;
        int fare = lookup(SHINKANSEN_SURCHARGE, km);
        return fare > 0 ? fare : 8140;
    }

    /** JR 이외 철도의 요금(성인, 엔) */
    public static int fare(RailCategory category, double km) {
        switch (category) {
            case JR:
            case SHINKANSEN:
                return jrFare(km);
            case SUBWAY: {
                int fare = lookup(SUBWAY_FARES, km);
                return fare > 0 ? fare : 400;
            }
            case GUIDED: {
                int fare = lookup(SUBWAY_FARES, km);
                return roundTo10((fare > 0 ? fare : 400) * 1.2);
            }
            case TRAM:
                return km <= 10 ? 200 : 300;        // 대부분 균일 요금
            case CABLE:
                return 600;
            case THIRD_SECTOR:
                return roundTo10(privateFare(km) * 1.4);
            default:
                return privateFare(km);
        }
    }

    private static int privateFare(double km) {
        int fare = lookup(PRIVATE_FARES, km);
        return fare > 0 ? fare : roundTo10(980 + (km - 100) * 8);
    }

    /** 아동 요금: 성인의 절반, 10엔 단위 올림 */
    public static int childFare(int adultFare) {
        return (int) (Math.ceil(adultFare / 2.0 / 10.0) * 10);
    }

    /** 노선을 확인하지 못한 구간(버스 등)의 요금 추정. 시내버스 균일·구간 요금의 대표값이다. */
    public static int unknownLocalFare(double straightKm) {
        double km = straightKm * 1.25;
        if (km <= 2) return 220;
        if (km <= 5) return 250;
        if (km <= 10) return 320;
        if (km <= 20) return 500;
        return roundTo10(500 + (km - 20) * 25);
    }

    private static int roundTo10(double yen) {
        return (int) (Math.round(yen / 10.0) * 10);
    }
}
