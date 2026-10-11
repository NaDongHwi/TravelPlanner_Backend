package com.travel.planner.transit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 등록해 둔 요금 자료로 철도 요금을 계산한다. (DB·스프링과 무관한 순수 계산, 만든 뒤에는 바뀌지 않는다)
 *
 * 요금을 정하는 순서:
 *   1. 역 쌍 요금: "이 역에서 저 역까지 얼마"로 등록한 값. 운영사가 정한 특정 구간 운임처럼 거리로는 나오지 않는 값에 쓴다.
 *   2. 운영사 요금표: 운영사가 공개한 "몇 km 까지 얼마" 표에, 철도망에서 계산한 거리를 넣는다.
 *      거리는 선로 도형에서 잰 값이라 구간 경계 근처에서는 실제 요금과 한 단계 다를 수 있다.
 *   3. 둘 다 없으면 {@link RailFareModel} 의 종류별 대표 요금표로 추정한다.
 *
 * 신칸센은 운임을 재는 거리(영업킬로)가 실제 선로 거리보다 길다. 노선별 영업킬로 표가 등록되어 있으면 그 거리로 요금을 낸다.
 */
public final class FareBook {

    /** 요금이 어떤 근거의 값인지. 뒤로 갈수록 덜 확실하다 */
    public enum Basis {
        /** 운영사가 정한 값 그대로 (역 쌍 요금, GTFS 요금표) */
        OFFICIAL("official"),
        /** 운영사 요금표 + 계산한 거리 */
        TABLE("table"),
        /** 종류별 대표 요금표로 추정 */
        ESTIMATE("estimate");

        public final String code;

        Basis(String code) {
            this.code = code;
        }

        public Basis weaker(Basis other) {
            return other.ordinal() > ordinal() ? other : this;
        }
    }

    /** 운영사 요금표 한 장 */
    public static final class Table {
        public String key;
        public String name;
        /** 이 표를 쓰는 운영사 (철도망의 운영사 이름 원문). 하나 이상 */
        public Set<String> operators = new LinkedHashSet<>();
        /** 비어 있으면 종류를 가리지 않는다 */
        public Set<RailCategory> categories = EnumSet.noneOf(RailCategory.class);
        /** 비어 있지 않으면, 탄 노선 이름이 모두 이 중 하나를 포함해야 한다 */
        public List<String> lines = new ArrayList<>();
        /** 탄 노선 이름이 이 중 하나라도 포함하면 이 표를 쓰지 않는다 */
        public List<String> exceptLines = new ArrayList<>();
        /** 비어 있지 않으면, 타고 내린 역이 모두 이 범위 안에 있어야 한다: {남쪽 위도, 서쪽 경도, 북쪽 위도, 동쪽 경도} */
        public List<double[]> boxes = new ArrayList<>();
        /** {이 거리(km)까지, 요금(엔)}. 거리 오름차순. 거리는 1km 미만을 올린 정수로 찾는다 */
        public int[][] bands = new int[0][];
        /** 이 역에서 타거나 내리면 더 내는 금액 (공항선 가산운임 등). 양쪽 다 해당하면 큰 쪽만 */
        public Map<String, Integer> stationAdds = new LinkedHashMap<>();
        /** 아동 요금: true 면 10엔 미만 버림(JR), false 면 올림(대부분의 사철·지하철) */
        public boolean childRoundDown;

        /** 표에 있는 거리면 요금, 표의 마지막 구간을 넘으면 -1 */
        public int fare(double km) {
            int whole = Math.max(1, (int) Math.ceil(km - 1e-9));
            for (int[] band : bands) {
                if (whole <= band[0]) return band[1];
            }
            return -1;
        }

        boolean inArea(double lat, double lng) {
            if (boxes.isEmpty()) return true;
            for (double[] box : boxes) {
                if (lat >= box[0] && lat <= box[2] && lng >= box[1] && lng <= box[3]) return true;
            }
            return false;
        }
    }

    /** 역 쌍 요금 (방향 구분 없음) */
    public static final class Pair {
        /** 요금 묶음: JR 은 "JR", 그 외에는 운영사 이름 원문 */
        public String group;
        public String stationA;
        public String stationB;
        public int adultYen;
        /** 없으면 성인의 절반으로 계산 */
        public Integer childYen;
        /** true 면 운임이 아니라 신칸센 자유석 특급요금 */
        public boolean surcharge;
    }

    /** 한 묶음(이어서 탄 같은 요금 단위)의 요금 */
    public static final class Quote {
        /** 운임 + 특급요금 */
        public int fareYen;
        public int childFareYen;
        /** 그중 신칸센 자유석 특급요금 (없으면 0) */
        public int surchargeYen;
        public Basis basis;
        /** 쓴 요금표의 키 (역 쌍 요금이나 추정이면 null) */
        public String tableKey;
        /** 요금을 잰 거리(km) */
        public double fareKm;
    }

    public static final FareBook EMPTY = new FareBook(Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());

    private final List<Table> tables;
    private final Map<String, Pair> fares = new HashMap<>();
    private final Map<String, Pair> surcharges = new HashMap<>();
    /** 노선 이름 → (역 이름 → 누적 영업킬로) */
    private final Map<String, Map<String, Double>> lineKm;

    public FareBook(List<Table> tables, List<Pair> pairs, Map<String, Map<String, Double>> lineKm) {
        this.tables = new ArrayList<>(tables);
        for (Pair pair : pairs) {
            (pair.surcharge ? surcharges : fares).put(pairKey(pair.group, pair.stationA, pair.stationB), pair);
        }
        this.lineKm = new HashMap<>(lineKm);
    }

    public boolean isEmpty() {
        return tables.isEmpty() && fares.isEmpty() && surcharges.isEmpty() && lineKm.isEmpty();
    }

    public int tableCount() { return tables.size(); }
    public int pairCount() { return fares.size() + surcharges.size(); }
    public int lineKmCount() { return lineKm.size(); }

    private static String pairKey(String group, String a, String b) {
        return a.compareTo(b) <= 0 ? group + "|" + a + "|" + b : group + "|" + b + "|" + a;
    }

    /**
     * 요금을 재는 거리. 노선의 영업킬로 표에 두 역이 모두 있으면 그 차이를, 아니면 철도망에서 계산한 거리를 쓴다.
     */
    public double fareKm(RailGraph.Step ride) {
        Map<String, Double> km = ride.line == null ? null : lineKm.get(ride.line);
        if (km != null) {
            Double from = km.get(ride.from);
            Double to = km.get(ride.to);
            if (from != null && to != null) return Math.abs(to - from);
        }
        return ride.km;
    }

    /**
     * 철도망 구간 한 묶음의 요금.
     * @param rides 이어서 탄 같은 요금 단위의 승차 단계들 (JR 끼리, 또는 한 운영사끼리). 하나 이상
     */
    public Quote price(List<RailGraph.Step> rides) {
        RailGraph.Step first = rides.get(0);
        RailGraph.Step last = rides.get(rides.size() - 1);
        String group = RailFareModel.fareGroup(first.category, first.operator);
        boolean jr = "JR".equals(group);

        double km = 0;
        double shinkansenKm = 0;
        Map<String, Double> kmByOperator = new LinkedHashMap<>();
        RailGraph.Step shinkansenFirst = null;
        RailGraph.Step shinkansenLast = null;
        for (RailGraph.Step ride : rides) {
            double d = fareKm(ride);
            km += d;
            kmByOperator.merge(ride.operator == null ? "" : ride.operator, d, Double::sum);
            if (ride.category == RailCategory.SHINKANSEN) {
                shinkansenKm += d;
                if (shinkansenFirst == null) shinkansenFirst = ride;
                shinkansenLast = ride;
            }
        }
        // JR 은 회사가 섞여 있으면 가장 길게 탄 회사의 표를 쓴다
        String operator = first.operator;
        if (jr) {
            double most = -1;
            for (Map.Entry<String, Double> e : kmByOperator.entrySet()) {
                if (e.getValue() > most) {
                    most = e.getValue();
                    operator = e.getKey();
                }
            }
        }
        // JR 재래선과 신칸센이 섞인 구간은 JR 로 계산한다
        RailCategory category = last.category == RailCategory.SHINKANSEN ? RailCategory.JR : last.category;

        Quote quote = new Quote();
        quote.fareKm = km;
        int base;
        Integer childBase = null;
        boolean roundDown = false;

        Table table = findTable(operator, rides);
        int tableFare = table == null ? -1 : table.fare(km);
        Pair pair = first.from == null || last.to == null ? null : fares.get(pairKey(group, first.from, last.to));
        if (pair != null) {
            // 이름만 같은 다른 역의 요금이 잘못 걸리는 것을 막는다: 거리로 계산한 값과 두 배 넘게 다르면 그 역 쌍이 아니다
            int byDistance = tableFare >= 0 ? tableFare : RailFareModel.fare(category, operator, km);
            if (!plausible(pair.adultYen, byDistance)) pair = null;
        }
        if (pair != null) {
            base = pair.adultYen;
            childBase = pair.childYen;
            quote.basis = Basis.OFFICIAL;
        } else if (tableFare >= 0) {
            base = tableFare + stationAdd(table, first.from, last.to);
            roundDown = table.childRoundDown;
            quote.basis = Basis.TABLE;
            quote.tableKey = table.key;
        } else {
            base = RailFareModel.fare(category, operator, km);
            quote.basis = Basis.ESTIMATE;
        }

        int surcharge = 0;
        if (shinkansenKm > 0) {
            Pair extra = surcharges.get(pairKey("JR", shinkansenFirst.from, shinkansenLast.to));
            if (extra != null && !plausible(extra.adultYen, RailFareModel.shinkansenSurcharge(shinkansenKm))) extra = null;
            if (extra != null) {
                surcharge = extra.adultYen;
            } else {
                surcharge = RailFareModel.shinkansenSurcharge(shinkansenKm);
                quote.basis = quote.basis.weaker(Basis.ESTIMATE);
            }
        }

        quote.surchargeYen = surcharge;
        quote.fareYen = base + surcharge;
        if (quote.basis == Basis.ESTIMATE && pair == null && tableFare < 0) {
            // 등록된 자료가 전혀 없을 때는 이전과 같은 방식(합계의 절반, 10엔 올림)으로 계산한다
            quote.childFareYen = RailFareModel.childFare(quote.fareYen);
        } else {
            int child = childBase != null ? childBase : half(base, roundDown);
            quote.childFareYen = child + half(surcharge, true);
        }
        return quote;
    }

    private Table findTable(String operator, List<RailGraph.Step> rides) {
        Table best = null;
        for (Table table : tables) {
            if (!table.operators.contains(operator)) continue;
            if (!matches(table, rides)) continue;
            // 범위가 정해진 표(도시권 특정 구간)가 회사 전체 표보다 먼저다. 같은 조건이면 먼저 등록된 것
            if (best == null || (!table.boxes.isEmpty() && best.boxes.isEmpty())) best = table;
        }
        return best;
    }

    private static boolean matches(Table table, List<RailGraph.Step> rides) {
        for (RailGraph.Step ride : rides) {
            RailCategory category = ride.category == RailCategory.SHINKANSEN ? RailCategory.JR : ride.category;
            if (!table.categories.isEmpty() && !table.categories.contains(category)) return false;
            String line = ride.line == null ? "" : ride.line;
            if (!table.lines.isEmpty() && !containsAny(line, table.lines)) return false;
            if (containsAny(line, table.exceptLines)) return false;
            if (!table.boxes.isEmpty()) {
                if (!ride.hasCoordinates) return false;
                if (!table.inArea(ride.fromLat, ride.fromLng) || !table.inArea(ride.toLat, ride.toLng)) return false;
            }
        }
        return true;
    }

    private static boolean containsAny(String text, List<String> parts) {
        for (String part : parts) {
            if (!part.isEmpty() && text.contains(part)) return true;
        }
        return false;
    }

    private static int stationAdd(Table table, String from, String to) {
        if (table.stationAdds.isEmpty()) return 0;
        int a = from == null ? 0 : table.stationAdds.getOrDefault(from, 0);
        int b = to == null ? 0 : table.stationAdds.getOrDefault(to, 0);
        return Math.max(a, b);
    }

    /** 등록된 값이 거리로 계산한 값의 절반~두 배 안에 있는가 */
    private static boolean plausible(int registered, int byDistance) {
        return byDistance <= 0 || (registered * 2 >= byDistance && registered <= byDistance * 2);
    }

    /** 절반. 10엔 단위로 내리거나 올린다 */
    static int half(int yen, boolean roundDown) {
        if (yen <= 0) return 0;
        return roundDown ? (yen / 2) / 10 * 10 : (int) (Math.ceil(yen / 2.0 / 10.0) * 10);
    }

    // =====================================================================
    // 문자열 ↔ 값 (DB 와 기본 자료 파일에 쓰는 표기)
    // =====================================================================

    /** "3:150,6:190,10:200" → {{3,150},{6,190},{10,200}}. 형식이 틀리면 IllegalArgumentException */
    public static int[][] parseBands(String text) {
        List<int[]> bands = new ArrayList<>();
        int previous = 0;
        for (String part : split(text, ",")) {
            String[] kv = part.split(":");
            if (kv.length != 2) throw new IllegalArgumentException("요금 구간은 \"거리:요금\" 형식이어야 합니다: " + part);
            int km;
            int yen;
            try {
                km = Integer.parseInt(kv[0].trim());
                yen = Integer.parseInt(kv[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("요금 구간의 숫자를 읽을 수 없습니다: " + part);
            }
            if (km <= previous) throw new IllegalArgumentException("요금 구간의 거리는 점점 커져야 합니다: " + part);
            if (yen < 0) throw new IllegalArgumentException("요금은 0 이상이어야 합니다: " + part);
            previous = km;
            bands.add(new int[]{km, yen});
        }
        if (bands.isEmpty()) throw new IllegalArgumentException("요금 구간이 비어 있습니다.");
        return bands.toArray(new int[0][]);
    }

    public static String formatBands(int[][] bands) {
        StringBuilder sb = new StringBuilder();
        for (int[] band : bands) {
            if (sb.length() > 0) sb.append(',');
            sb.append(band[0]).append(':').append(band[1]);
        }
        return sb.toString();
    }

    /** "34.2,134.5,35.1,136.0;35.5,139.6,35.8,139.9" → 범위 목록. 비어 있으면 빈 목록 */
    public static List<double[]> parseBoxes(String text) {
        List<double[]> boxes = new ArrayList<>();
        for (String part : split(text, ";")) {
            String[] n = part.split(",");
            if (n.length != 4) throw new IllegalArgumentException("범위는 \"남위도,서경도,북위도,동경도\" 형식이어야 합니다: " + part);
            double[] box = new double[4];
            try {
                for (int i = 0; i < 4; i++) box[i] = Double.parseDouble(n[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("범위의 숫자를 읽을 수 없습니다: " + part);
            }
            if (box[0] > box[2] || box[1] > box[3]) throw new IllegalArgumentException("범위의 남·서쪽 값이 북·동쪽 값보다 큽니다: " + part);
            boxes.add(box);
        }
        return boxes;
    }

    public static String formatBoxes(List<double[]> boxes) {
        StringBuilder sb = new StringBuilder();
        for (double[] box : boxes) {
            if (sb.length() > 0) sb.append(';');
            sb.append(box[0]).append(',').append(box[1]).append(',').append(box[2]).append(',').append(box[3]);
        }
        return sb.toString();
    }

    /** "関西空港:220,りんくうタウン:160" → 역 이름별 금액 */
    public static Map<String, Integer> parseAmounts(String text) {
        Map<String, Integer> amounts = new LinkedHashMap<>();
        for (String part : split(text, ",")) {
            int colon = part.lastIndexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("\"이름:금액\" 형식이어야 합니다: " + part);
            try {
                amounts.put(part.substring(0, colon).trim(), Integer.parseInt(part.substring(colon + 1).trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("금액을 읽을 수 없습니다: " + part);
            }
        }
        return amounts;
    }

    /** "東京:0,品川:6.8" → 역 이름별 누적 거리 */
    public static Map<String, Double> parseKm(String text) {
        Map<String, Double> km = new LinkedHashMap<>();
        for (String part : split(text, ",")) {
            int colon = part.lastIndexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("\"역:거리\" 형식이어야 합니다: " + part);
            try {
                km.put(part.substring(0, colon).trim(), Double.parseDouble(part.substring(colon + 1).trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("거리를 읽을 수 없습니다: " + part);
            }
        }
        if (km.size() < 2) throw new IllegalArgumentException("역이 둘 이상 있어야 합니다.");
        return km;
    }

    /** "JR,SUBWAY" → 종류 집합. 모르는 이름이 있으면 IllegalArgumentException */
    public static Set<RailCategory> parseCategories(String text) {
        Set<RailCategory> categories = EnumSet.noneOf(RailCategory.class);
        for (String part : split(text, ",")) {
            try {
                categories.add(RailCategory.valueOf(part.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("모르는 교통수단 종류입니다: " + part);
            }
        }
        return categories;
    }

    /** 구분자로 나누고 빈 조각은 버린다 */
    public static List<String> split(String text, String separator) {
        List<String> parts = new ArrayList<>();
        if (text == null) return parts;
        for (String part : text.split(java.util.regex.Pattern.quote(separator))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        return parts;
    }
}
