package com.travel.planner.transit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * GTFS 파일 하나를 읽어 정리한 결과.
 * 시각표 원본(stop_times 수십만~수백만 줄)은 저장하지 않고, "정류장 순서가 같은 운행"을 하나의 패턴으로 묶어
 * 구간 소요 시간·운행 횟수·요금표만 남긴다.
 */
public final class GtfsFeed {

    public static final class Stop {
        public int index;
        public String id;
        public String name;        // 일본어 원문
        public String nameKo;      // 한국어 표기 (원본의 ko 번역 → 없으면 읽기(가나)를 한글로 → 그것도 없으면 null)
        public double latitude;
        public double longitude;
    }

    public static final class Pattern {
        /** 어느 피드(파일)에서 왔는지. 여러 피드를 한 그래프에 합칠 때 채운다 */
        public String feedKey;
        /** 그 피드의 유효 기간이 지났는가 (지났으면 날짜별 운행 기간을 따지지 않는다) */
        public boolean feedExpired;
        public String routeId;
        public String routeName;       // 계통 번호·노선명 (짧은 이름 우선)
        public String routeLongName;   // 긴 이름 (없으면 null)
        public String agency;
        public RailCategory category;
        public String headsign;        // 행선지 (일본어 원문)
        /** 지나는 정류장 (Stop.index) */
        public int[] stops;
        /** 첫 정류장 출발부터 각 정류장 도착까지의 평균 시간(분) */
        public float[] cumMinutes;
        /** 정류장별 제한: 1 = 승차 불가, 2 = 하차 불가 (비트 합) */
        public byte[] flags;
        /** 하루 운행 횟수: [평일, 토요일, 일요일·공휴일] */
        public final int[] trips = new int[3];
        /** 첫차·막차가 첫 정류장을 떠나는 시각 (0시부터의 분) */
        public int firstDeparture;
        public int lastDeparture;
        /** 운행 기간. 기간이 따로 없으면(연중 운행) null */
        public LocalDate serviceFrom;
        public LocalDate serviceTo;
        /** 정해진 요일에 매번 다니지 않는 노선 (특정일만 운행) */
        public boolean irregular;
        /** 어디서 타고 내려도 같은 요금이면 그 값, 아니면 null */
        public Integer flatFare;
        /** 구간 요금표: i 번째에서 타서 j 번째(i<j)에서 내릴 때의 요금. 모르면 -1. 균일 요금이거나 요금 정보가 없으면 null */
        public int[] fares;

        public int fare(int from, int to) {
            if (flatFare != null) return flatFare;
            if (fares == null || from < 0 || to <= from || to >= stops.length) return -1;
            return fares[fareIndex(stops.length, from, to)];
        }
    }

    /** 위쪽 삼각 행렬을 한 줄로 편 배열에서의 위치 */
    public static int fareIndex(int n, int from, int to) {
        return from * n - from * (from + 1) / 2 + (to - from - 1);
    }

    public final List<Stop> stops = new ArrayList<>();
    public final List<Pattern> patterns = new ArrayList<>();
    public final List<String> agencies = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public String publisher;
    public String version;
    public LocalDate validFrom;
    public LocalDate validTo;
    /** 유효 기간이 이미 지난 파일 */
    public boolean expired;
    /** 철도·지하철 노선까지 읽었는가 (기본은 건너뛴다) */
    public boolean includesRail;

    public int routeCount;
    public int tripCount;
    public int stopTimeCount;
    public int fareRuleCount;
    /** 승하차 가능한 정류장 쌍 가운데 요금을 아는 비율(%) */
    public int fareCoveragePercent;
}
