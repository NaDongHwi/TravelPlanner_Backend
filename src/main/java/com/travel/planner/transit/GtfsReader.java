package com.travel.planner.transit;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GTFS(GTFS-JP 포함) zip 파일을 읽어 {@link GtfsFeed} 로 정리한다. DB·스프링과 무관한 순수 계산이다.
 *
 * 하는 일
 *  1. 정류장·노선·운행(trips)·시각(stop_times)을 읽는다.
 *  2. 정류장 순서(와 승하차 제한)가 같은 운행을 하나의 "패턴"으로 묶고, 구간별 평균 소요 시간을 구한다.
 *  3. 운행 달력(calendar, calendar_dates)을 날짜별로 펼쳐, 패턴마다 평일 / 토요일 / 일요일·공휴일의 하루 운행 횟수와 운행 기간을 구한다.
 *  4. 요금표(fare_attributes, fare_rules)로 패턴마다 "어디서 타서 어디서 내리면 얼마"를 계산해 둔다.
 *  5. 정류장 읽기(translations 의 ja-Hrkt)를 한글 표기로 바꾼다.
 *
 * 열차·버스의 개별 출발 시각은 남기지 않는다. 그래서 "몇 시 몇 분 차"는 알 수 없고, 소요 시간·운행 횟수·요금만 안다.
 */
public final class GtfsReader {

    private GtfsReader() {}

    /** stop_times 줄 수 상한 (도쿄 도에이 버스가 약 200만 줄) */
    static final int MAX_STOP_TIMES = 12_000_000;
    /** 압축을 푼 파일 하나의 크기 상한 (압축 폭탄 방지) */
    static final long MAX_ENTRY_BYTES = 1_500_000_000L;
    /** 운행 달력을 펼쳐 보는 기간(일) */
    static final int SCAN_DAYS = 370;
    /** 운행 기간의 끝이 조사 기간의 끝에서 이 날수 안이면 "기간 제한 없음"으로 본다 */
    static final int OPEN_END_DAYS = 14;

    /** 철도·지하철 노선은 빼고 읽는다 (전국 철도망과 겹치기 때문). */
    public static GtfsFeed read(Path zipFile, LocalDate today) throws IOException {
        return read(zipFile, today, false);
    }

    /**
     * @param includeRail 철도·지하철·모노레일 노선(route_type 1, 2, 12 등)도 읽을지.
     *                    기본은 읽지 않는다: 그 노선들은 전국 철도망에 이미 있고, 한 회사의 노선을 갈아탈 때 요금을 이어서 계산하는 것은
     *                    철도망 쪽만 한다. 노선이 하나뿐인 모노레일처럼 갈아탈 일이 없는 피드에서만 true 로 쓴다.
     */
    public static GtfsFeed read(Path zipFile, LocalDate today, boolean includeRail) throws IOException {
        ZipFile zip;
        try {
            zip = new ZipFile(zipFile.toFile());
        } catch (IOException | IllegalArgumentException e) {
            // 파일 이름이 UTF-8 이 아닌 zip (일본어 윈도우에서 만든 것)
            zip = new ZipFile(zipFile.toFile(), Charset.forName("MS932"));
        }
        try {
            return new Loader(zip, today, includeRail).load();
        } finally {
            zip.close();
        }
    }

    // =====================================================================
    // 읽는 동안 쓰는 자료 구조
    // =====================================================================

    /** 크기가 늘어나는 int 배열 */
    private static final class Ints {
        int[] data = new int[1024];
        int size;

        void add(int value) {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = value;
        }
    }

    /** long → int 해시표 (요금 규칙이 수백만 줄일 수 있어 객체를 만들지 않는다). 같은 키가 다시 오면 큰 값을 남긴다. */
    private static final class FareMap {
        long[] keys = new long[1 << 12];
        int[] values = new int[1 << 12];
        int size;
        int conflicts;

        FareMap() {
            Arrays.fill(keys, -1L);
        }

        void put(long key, int value) {
            if (size * 2 >= keys.length) grow();
            int slot = slot(keys, key);
            if (keys[slot] == key) {
                if (values[slot] != value) conflicts++;
                values[slot] = Math.max(values[slot], value);
                return;
            }
            keys[slot] = key;
            values[slot] = value;
            size++;
        }

        int get(long key) {
            int slot = slot(keys, key);
            return keys[slot] == key ? values[slot] : -1;
        }

        private static int slot(long[] keys, long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            int slot = (int) (h >>> 20) & (keys.length - 1);
            while (keys[slot] != -1L && keys[slot] != key) slot = (slot + 1) & (keys.length - 1);
            return slot;
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldValues = values;
            keys = new long[oldKeys.length * 2];
            values = new int[oldKeys.length * 2];
            Arrays.fill(keys, -1L);
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] == -1L) continue;
                int slot = slot(keys, oldKeys[i]);
                keys[slot] = oldKeys[i];
                values[slot] = oldValues[i];
            }
        }
    }

    private static final class Service {
        final boolean[] weekdays = new boolean[7];   // 월=0 … 일=6
        LocalDate start;
        LocalDate end;
        final Set<LocalDate> added = new HashSet<>();
        final Set<LocalDate> removed = new HashSet<>();

        boolean activeOn(LocalDate date) {
            if (removed.contains(date)) return false;
            if (added.contains(date)) return true;
            return start != null && end != null && !date.isBefore(start) && !date.isAfter(end)
                    && weekdays[date.getDayOfWeek().getValue() - 1];
        }
    }

    private static final class Route {
        String id;
        String shortName;
        String longName;
        String agency;
        int type;
    }

    private static final class PatternKey {
        final int route;
        final int[] stops;
        final byte[] flags;
        final int hash;

        PatternKey(int route, int[] stops, byte[] flags) {
            this.route = route;
            this.stops = stops;
            this.flags = flags;
            this.hash = (route * 31 + Arrays.hashCode(stops)) * 31 + Arrays.hashCode(flags);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof PatternKey)) return false;
            PatternKey other = (PatternKey) o;
            return route == other.route && Arrays.equals(stops, other.stops) && Arrays.equals(flags, other.flags);
        }
    }

    private static final class PatternAcc {
        PatternKey key;
        double[] sumSeconds;
        int samples;
        final Map<Integer, Integer> tripsByService = new HashMap<>();
        final Map<String, Integer> headsigns = new HashMap<>();
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
    }

    // =====================================================================
    // 본체
    // =====================================================================

    private static final class Loader {
        final ZipFile zip;
        final LocalDate today;
        final Map<String, ZipEntry> files = new HashMap<>();
        final GtfsFeed feed = new GtfsFeed();

        final Map<String, String> agencyNames = new LinkedHashMap<>();
        final Map<String, Integer> routeIndex = new HashMap<>();
        final List<Route> routes = new ArrayList<>();

        final Map<String, Integer> stopIndex = new HashMap<>();
        final List<String> stopIds = new ArrayList<>();
        final List<String> stopNames = new ArrayList<>();
        final List<double[]> stopCoords = new ArrayList<>();
        final List<String> stopZones = new ArrayList<>();

        final Map<String, Integer> serviceIndex = new HashMap<>();
        final List<Service> services = new ArrayList<>();

        final Map<String, Integer> tripIndex = new HashMap<>();
        final Ints tripRoute = new Ints();
        final Ints tripService = new Ints();
        final List<String> tripHeadsign = new ArrayList<>();

        final Ints stTrip = new Ints();
        final Ints stSeq = new Ints();
        final Ints stStop = new Ints();
        final Ints stArrive = new Ints();
        final Ints stDepart = new Ints();
        final Ints stFlags = new Ints();

        /** frequencies.txt: 운행 번호 → {횟수, 첫 출발(초), 마지막 출발(초)} */
        final Map<Integer, int[]> frequencies = new HashMap<>();

        final boolean includeRail;
        int skippedStops;
        int unknownStopRefs;
        int skippedTrips;
        int skippedRailRoutes;

        Loader(ZipFile zip, LocalDate today, boolean includeRail) {
            this.zip = zip;
            this.includeRail = includeRail;
            this.today = today == null ? LocalDate.now() : today;
            zip.stream().forEach(entry -> {
                if (entry.isDirectory() || entry.getName().contains("__MACOSX")) return;
                String name = entry.getName().replace('\\', '/');
                String base = name.substring(name.lastIndexOf('/') + 1).toLowerCase();
                ZipEntry existing = files.get(base);
                // 같은 이름이 여러 폴더에 있으면 가장 바깥의 것을 쓴다
                if (existing == null || depth(name) < depth(existing.getName().replace('\\', '/'))) files.put(base, entry);
            });
        }

        private static int depth(String name) {
            int count = 0;
            for (int i = 0; i < name.length(); i++) if (name.charAt(i) == '/') count++;
            return count;
        }

        GtfsCsv open(String name, boolean required) throws IOException {
            ZipEntry entry = files.get(name);
            if (entry == null) {
                if (required) throw new IOException("GTFS 파일이 아닙니다: " + name + " 가 없습니다.");
                return null;
            }
            if (entry.getSize() > MAX_ENTRY_BYTES) throw new IOException(name + " 가 너무 큽니다 (" + entry.getSize() / 1_000_000 + "MB).");
            return new GtfsCsv(zip.getInputStream(entry));
        }

        GtfsFeed load() throws IOException {
            feed.includesRail = includeRail;
            readAgencies();
            readRoutes();
            readStops();
            readCalendars();
            readTrips();
            readStopTimes();
            readFrequencies();
            readFeedInfo();
            detectStaleCalendars();

            List<PatternAcc> accs = buildPatterns();
            if (accs.isEmpty() && skippedRailRoutes > 0) {
                throw new IOException("철도·지하철 노선만 있는 피드입니다. 이 노선들은 전국 철도망으로 계산하므로 가져오지 않습니다 (꼭 필요하면 includeRail=true).");
            }
            if (accs.isEmpty()) throw new IOException("운행 정보를 읽지 못했습니다 (정류장 좌표나 시각이 없는 파일일 수 있습니다).");
            List<GtfsFeed.Pattern> patterns = countService(accs);
            if (patterns.isEmpty()) throw new IOException("운행 달력에 실제로 다니는 날이 없습니다.");

            applyFares(patterns);
            finishStops(patterns);

            feed.patterns.addAll(patterns);
            feed.routeCount = routes.size();
            feed.tripCount = tripRoute.size;
            feed.stopTimeCount = stTrip.size;
            feed.agencies.addAll(new ArrayList<>(new java.util.LinkedHashSet<>(agencyNames.values())));
            if (skippedRailRoutes > 0) feed.warnings.add("철도·지하철 노선 " + skippedRailRoutes + "개는 전국 철도망과 겹치므로 가져오지 않았습니다.");
            if (skippedStops > 0) feed.warnings.add("좌표가 없거나 일본 밖인 정류장 " + skippedStops + "개를 뺐습니다.");
            if (unknownStopRefs > 0) feed.warnings.add("정류장을 찾을 수 없는 시각 " + unknownStopRefs + "줄을 건너뛰었습니다.");
            if (skippedTrips > 0) feed.warnings.add("시각이 없거나 정류장이 2개 미만인 운행 " + skippedTrips + "개를 건너뛰었습니다.");
            return feed;
        }

        // ---------------------------------------------------------------
        // 1. 기본 파일
        // ---------------------------------------------------------------

        void readAgencies() throws IOException {
            try (GtfsCsv csv = open("agency.txt", false)) {
                if (csv == null) return;
                while (csv.next()) {
                    String name = csv.get("agency_name");
                    if (!name.isEmpty()) agencyNames.put(csv.get("agency_id"), name);
                }
            }
        }

        void readRoutes() throws IOException {
            String onlyAgency = agencyNames.isEmpty() ? "" : agencyNames.values().iterator().next();
            try (GtfsCsv csv = open("routes.txt", true)) {
                while (csv.next()) {
                    String id = csv.get("route_id");
                    if (id.isEmpty() || routeIndex.containsKey(id)) continue;
                    Route route = new Route();
                    route.id = id;
                    route.shortName = csv.get("route_short_name");
                    route.longName = csv.get("route_long_name");
                    String agency = agencyNames.get(csv.get("agency_id"));
                    route.agency = agency != null ? agency : onlyAgency;
                    route.type = parseInt(csv.get("route_type"), 3);
                    if (!includeRail && isRailNetworkType(route.type)) {
                        skippedRailRoutes++;        // routeIndex 에 넣지 않으면 이 노선의 운행도 함께 빠진다
                        continue;
                    }
                    routeIndex.put(id, routes.size());
                    routes.add(route);
                }
            }
        }

        void readStops() throws IOException {
            try (GtfsCsv csv = open("stops.txt", true)) {
                while (csv.next()) {
                    String id = csv.get("stop_id");
                    if (id.isEmpty() || stopIndex.containsKey(id)) continue;
                    double lat = parseDouble(csv.get("stop_lat"));
                    double lng = parseDouble(csv.get("stop_lon"));
                    if (Double.isNaN(lat) || Double.isNaN(lng) || lat < 20 || lat > 46 || lng < 122 || lng > 154) {
                        // 승강장이 아닌 상위 정류장(location_type=1)은 시각표에 쓰이지 않으므로 세지 않는다
                        if (!"1".equals(csv.get("location_type"))) skippedStops++;
                        continue;
                    }
                    String name = csv.get("stop_name");
                    String zone = csv.get("zone_id");
                    stopIndex.put(id, stopIds.size());
                    stopIds.add(id);
                    stopNames.add(name.isEmpty() ? id : name);
                    stopCoords.add(new double[]{lat, lng});
                    stopZones.add(zone.isEmpty() ? id : zone);
                }
            }
        }

        Service service(String id) {
            Integer index = serviceIndex.get(id);
            if (index != null) return services.get(index);
            serviceIndex.put(id, services.size());
            Service service = new Service();
            services.add(service);
            return service;
        }

        void readCalendars() throws IOException {
            String[] days = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
            try (GtfsCsv csv = open("calendar.txt", false)) {
                if (csv != null) {
                    while (csv.next()) {
                        String id = csv.get("service_id");
                        LocalDate start = parseDate(csv.get("start_date"));
                        LocalDate end = parseDate(csv.get("end_date"));
                        if (id.isEmpty() || start == null || end == null) continue;
                        Service service = service(id);
                        service.start = start;
                        service.end = end;
                        for (int i = 0; i < 7; i++) service.weekdays[i] = "1".equals(csv.get(days[i]));
                    }
                }
            }
            try (GtfsCsv csv = open("calendar_dates.txt", false)) {
                if (csv != null) {
                    while (csv.next()) {
                        String id = csv.get("service_id");
                        LocalDate date = parseDate(csv.get("date"));
                        if (id.isEmpty() || date == null) continue;
                        String type = csv.get("exception_type");
                        if ("1".equals(type)) service(id).added.add(date);
                        else if ("2".equals(type)) service(id).removed.add(date);
                    }
                }
            }
            if (services.isEmpty()) throw new IOException("운행 달력(calendar.txt / calendar_dates.txt)이 없습니다.");
        }

        void readTrips() throws IOException {
            try (GtfsCsv csv = open("trips.txt", true)) {
                while (csv.next()) {
                    String id = csv.get("trip_id");
                    Integer route = routeIndex.get(csv.get("route_id"));
                    Integer service = serviceIndex.get(csv.get("service_id"));
                    if (id.isEmpty() || route == null || service == null || tripIndex.containsKey(id)) continue;
                    tripIndex.put(id, tripRoute.size);
                    tripRoute.add(route);
                    tripService.add(service);
                    tripHeadsign.add(csv.get("trip_headsign"));
                }
            }
        }

        void readStopTimes() throws IOException {
            try (GtfsCsv csv = open("stop_times.txt", true)) {
                while (csv.next()) {
                    Integer trip = tripIndex.get(csv.get("trip_id"));
                    if (trip == null) continue;
                    Integer stop = stopIndex.get(csv.get("stop_id"));
                    if (stop == null) {
                        unknownStopRefs++;
                        continue;
                    }
                    if (stTrip.size >= MAX_STOP_TIMES) throw new IOException("stop_times.txt 가 너무 큽니다 (" + MAX_STOP_TIMES + "줄 초과).");
                    int pickup = parseInt(csv.get("pickup_type"), 0);
                    int dropOff = parseInt(csv.get("drop_off_type"), 0);
                    stTrip.add(trip);
                    stSeq.add(parseInt(csv.get("stop_sequence"), stTrip.size));
                    stStop.add(stop);
                    stArrive.add(parseTime(csv.get("arrival_time")));
                    stDepart.add(parseTime(csv.get("departure_time")));
                    stFlags.add((pickup == 1 ? 1 : 0) | (dropOff == 1 ? 2 : 0));
                }
            }
        }

        void readFrequencies() throws IOException {
            try (GtfsCsv csv = open("frequencies.txt", false)) {
                if (csv == null) return;
                while (csv.next()) {
                    Integer trip = tripIndex.get(csv.get("trip_id"));
                    int start = parseTime(csv.get("start_time"));
                    int end = parseTime(csv.get("end_time"));
                    int headway = parseInt(csv.get("headway_secs"), 0);
                    if (trip == null || start < 0 || end <= start || headway <= 0) continue;
                    int[] f = frequencies.computeIfAbsent(trip, k -> new int[]{0, Integer.MAX_VALUE, Integer.MIN_VALUE});
                    // start 부터 headway 간격으로, end 가 되기 전까지 출발한다
                    int count = Math.max(1, (end - start + headway - 1) / headway);
                    f[0] += count;
                    f[1] = Math.min(f[1], start);
                    f[2] = Math.max(f[2], start + (count - 1) * headway);
                }
            }
        }

        void readFeedInfo() throws IOException {
            try (GtfsCsv csv = open("feed_info.txt", false)) {
                if (csv != null && csv.next()) {
                    feed.publisher = emptyToNull(csv.get("feed_publisher_name"));
                    feed.version = emptyToNull(csv.get("feed_version"));
                    feed.validFrom = parseDate(csv.get("feed_start_date"));
                    feed.validTo = parseDate(csv.get("feed_end_date"));
                }
            }
            // feed_info 에 기간이 없으면 운행 달력에서 구한다
            LocalDate min = null;
            LocalDate max = null;
            for (Service s : services) {
                if (s.start != null && (min == null || s.start.isBefore(min))) min = s.start;
                if (s.end != null && (max == null || s.end.isAfter(max))) max = s.end;
                for (LocalDate d : s.added) {
                    if (min == null || d.isBefore(min)) min = d;
                    if (max == null || d.isAfter(max)) max = d;
                }
            }
            if (feed.validFrom == null) feed.validFrom = min;
            if (feed.validTo == null) feed.validTo = max;
            if (feed.validFrom == null || feed.validTo == null || feed.validTo.isBefore(feed.validFrom)) {
                throw new IOException("운행 기간을 알 수 없는 파일입니다.");
            }
            feed.expired = feed.validTo.isBefore(today);
        }

        /**
         * 파일의 유효 기간은 남아 있는데 운행 달력은 대부분 끝난 경우(오래된 달력이 섞인 파일)를 찾아낸다.
         * 운행의 80% 이상이 속한 달력이 이미 끝났으면, 그 달력들이 끝난 날을 기준일로 삼고 기간이 지난 파일로 다룬다.
         */
        void detectStaleCalendars() {
            if (feed.expired) return;
            int[] tripsPerService = new int[services.size()];
            for (int t = 0; t < tripService.size; t++) tripsPerService[tripService.data[t]]++;
            List<long[]> ends = new ArrayList<>();   // {달력이 끝나는 날, 운행 수}
            long total = 0;
            for (int s = 0; s < services.size(); s++) {
                if (tripsPerService[s] == 0) continue;
                Service service = services.get(s);
                LocalDate last = service.end;
                for (LocalDate d : service.added) if (last == null || d.isAfter(last)) last = d;
                if (last == null) continue;
                ends.add(new long[]{last.toEpochDay(), tripsPerService[s]});
                total += tripsPerService[s];
            }
            if (total == 0) return;
            ends.sort((a, b) -> Long.compare(a[0], b[0]));
            long seen = 0;
            for (long[] e : ends) {
                seen += e[1];
                if (seen * 10 >= total * 8) {
                    LocalDate bulkEnd = LocalDate.ofEpochDay(e[0]);
                    if (bulkEnd.isBefore(today)) {
                        feed.expired = true;
                        if (bulkEnd.isBefore(feed.validTo)) feed.validTo = bulkEnd;
                        feed.warnings.add("운행 달력의 대부분이 " + bulkEnd + " 에 끝났습니다. 그때의 시간표 기준으로 가져옵니다.");
                    }
                    return;
                }
            }
        }

        // ---------------------------------------------------------------
        // 2. 패턴 만들기
        // ---------------------------------------------------------------

        List<PatternAcc> buildPatterns() {
            int trips = tripRoute.size;
            int rows = stTrip.size;
            // 운행별로 줄을 모은다 (파일이 운행 순서대로 정렬되어 있지 않아도 된다)
            int[] offset = new int[trips + 1];
            for (int i = 0; i < rows; i++) offset[stTrip.data[i] + 1]++;
            for (int t = 0; t < trips; t++) offset[t + 1] += offset[t];
            int[] cursor = Arrays.copyOf(offset, trips);
            long[] ordered = new long[rows];
            for (int i = 0; i < rows; i++) {
                int trip = stTrip.data[i];
                ordered[cursor[trip]++] = ((long) stSeq.data[i] << 32) | (i & 0xFFFFFFFFL);
            }

            Map<PatternKey, PatternAcc> byKey = new LinkedHashMap<>();
            for (int trip = 0; trip < trips; trip++) {
                int from = offset[trip];
                int to = offset[trip + 1];
                int n = to - from;
                if (n < 2) {
                    skippedTrips++;
                    continue;
                }
                Arrays.sort(ordered, from, to);
                int[] stops = new int[n];
                byte[] flags = new byte[n];
                int[] time = new int[n];
                int firstDeparture = -1;
                for (int k = 0; k < n; k++) {
                    int row = (int) ordered[from + k];
                    stops[k] = stStop.data[row];
                    flags[k] = (byte) stFlags.data[row];
                    int arrive = stArrive.data[row];
                    int depart = stDepart.data[row];
                    time[k] = arrive >= 0 ? arrive : depart;
                    if (k == 0) firstDeparture = depart >= 0 ? depart : arrive;
                }
                if (firstDeparture < 0 || time[n - 1] < 0 || time[n - 1] - firstDeparture > 30 * 3600 || time[n - 1] < firstDeparture) {
                    skippedTrips++;
                    continue;
                }
                time[0] = firstDeparture;
                fillMissingTimes(time);

                PatternKey key = new PatternKey(tripRoute.data[trip], stops, flags);
                PatternAcc acc = byKey.get(key);
                if (acc == null) {
                    acc = new PatternAcc();
                    acc.key = key;
                    acc.sumSeconds = new double[n];
                    byKey.put(key, acc);
                }
                int previous = 0;
                for (int k = 0; k < n; k++) {
                    int elapsed = Math.max(previous, time[k] - firstDeparture);   // 시각이 뒤로 가는 오류는 바로잡는다
                    acc.sumSeconds[k] += elapsed;
                    previous = elapsed;
                }
                acc.samples++;

                int[] frequency = frequencies.get(trip);
                int count = frequency == null ? 1 : frequency[0];
                int first = frequency == null ? firstDeparture : frequency[1];
                int last = frequency == null ? firstDeparture : frequency[2];
                acc.tripsByService.merge(tripService.data[trip], count, Integer::sum);
                acc.first = Math.min(acc.first, first);
                acc.last = Math.max(acc.last, last);
                String headsign = tripHeadsign.get(trip);
                if (!headsign.isEmpty()) acc.headsigns.merge(headsign, count, Integer::sum);
            }
            return new ArrayList<>(byKey.values());
        }

        /** 중간에 시각이 빠진 정류장은 앞뒤 시각 사이를 고르게 나눠 채운다. */
        private static void fillMissingTimes(int[] time) {
            int known = 0;
            for (int k = 1; k < time.length; k++) {
                if (time[k] < 0) continue;
                for (int m = known + 1; m < k; m++) {
                    time[m] = time[known] + (int) ((long) (time[k] - time[known]) * (m - known) / (k - known));
                }
                known = k;
            }
        }

        // ---------------------------------------------------------------
        // 3. 운행 달력 → 요일별 횟수, 운행 기간
        // ---------------------------------------------------------------

        List<GtfsFeed.Pattern> countService(List<PatternAcc> accs) {
            // 조사 기간: 지금 유효한 파일은 오늘부터 1년, 기간이 지난 파일은 마지막 1년
            LocalDate start;
            LocalDate end;
            if (feed.expired) {
                end = feed.validTo;
                start = max(feed.validFrom, end.minusDays(SCAN_DAYS));
            } else {
                start = max(feed.validFrom, today.minusDays(7));
                end = min(feed.validTo, start.plusDays(SCAN_DAYS));
            }
            int days = (int) (end.toEpochDay() - start.toEpochDay()) + 1;

            int[] dayType = new int[days];
            boolean[][] active = new boolean[days][];
            for (int d = 0; d < days; d++) {
                LocalDate date = start.plusDays(d);
                dayType[d] = JapanHolidays.dayType(date);
                boolean[] row = new boolean[services.size()];
                for (int s = 0; s < row.length; s++) row[s] = services.get(s).activeOn(date);
                active[d] = row;
            }

            List<GtfsFeed.Pattern> result = new ArrayList<>();
            int dropped = 0;
            int[] counts = new int[days];
            for (PatternAcc acc : accs) {
                int firstDay = -1;
                int lastDay = -1;
                for (int d = 0; d < days; d++) {
                    int total = 0;
                    for (Map.Entry<Integer, Integer> e : acc.tripsByService.entrySet()) {
                        if (active[d][e.getKey()]) total += e.getValue();
                    }
                    counts[d] = total;
                    if (total > 0) {
                        if (firstDay < 0) firstDay = d;
                        lastDay = d;
                    }
                }
                if (firstDay < 0) {
                    dropped++;
                    continue;
                }

                GtfsFeed.Pattern pattern = toPattern(acc);
                boolean any = false;
                for (int type = 0; type < 3; type++) {
                    pattern.trips[type] = typical(counts, dayType, type, firstDay, lastDay, false);
                    any |= pattern.trips[type] > 0;
                }
                if (!any) {
                    // 그 요일에 절반도 다니지 않는 노선 (행사일·특정일 운행): 다니는 날의 횟수를 쓰고 표시해 둔다
                    pattern.irregular = true;
                    for (int type = 0; type < 3; type++) pattern.trips[type] = typical(counts, dayType, type, firstDay, lastDay, true);
                }
                if (!feed.expired) {
                    if (firstDay > OPEN_END_DAYS) pattern.serviceFrom = start.plusDays(firstDay);
                    if (lastDay < days - 1 - OPEN_END_DAYS) pattern.serviceTo = start.plusDays(lastDay);
                }
                result.add(pattern);
            }
            if (dropped > 0) feed.warnings.add("조사 기간(" + start + " ~ " + end + ")에 다니지 않는 노선 패턴 " + dropped + "개를 뺐습니다.");
            return result;
        }

        /**
         * 그 요일 종류의 대표 운행 횟수: 운행 기간 안의 해당 요일들을 늘어놓았을 때 가운데 값.
         * onlyRunning 이면 운행한 날만 놓고 본다.
         */
        private static int typical(int[] counts, int[] dayType, int type, int firstDay, int lastDay, boolean onlyRunning) {
            int[] values = new int[lastDay - firstDay + 1];
            int n = 0;
            for (int d = firstDay; d <= lastDay; d++) {
                if (dayType[d] != type) continue;
                if (onlyRunning && counts[d] == 0) continue;
                values[n++] = counts[d];
            }
            if (n == 0) return 0;
            Arrays.sort(values, 0, n);
            return values[n / 2];
        }

        GtfsFeed.Pattern toPattern(PatternAcc acc) {
            Route route = routes.get(acc.key.route);
            GtfsFeed.Pattern pattern = new GtfsFeed.Pattern();
            pattern.routeId = route.id;
            boolean hasShort = !route.shortName.isEmpty();
            pattern.routeName = hasShort ? route.shortName : (route.longName.isEmpty() ? route.id : route.longName);
            pattern.routeLongName = hasShort && !route.longName.isEmpty() && !route.longName.equals(route.shortName) ? route.longName : null;
            pattern.agency = route.agency;
            pattern.category = categoryOf(route.type, route.agency);
            pattern.stops = acc.key.stops;          // 아직 원본 정류장 번호 (finishStops 에서 다시 매긴다)
            pattern.flags = acc.key.flags;
            pattern.cumMinutes = new float[acc.key.stops.length];
            for (int k = 0; k < pattern.cumMinutes.length; k++) {
                pattern.cumMinutes[k] = (float) (Math.round(acc.sumSeconds[k] / acc.samples / 6.0) / 10.0);
            }
            pattern.firstDeparture = acc.first / 60;
            pattern.lastDeparture = acc.last / 60;
            String headsign = null;
            int best = 0;
            for (Map.Entry<String, Integer> e : acc.headsigns.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    headsign = e.getKey();
                }
            }
            pattern.headsign = headsign != null ? headsign : stopNames.get(acc.key.stops[acc.key.stops.length - 1]);
            return pattern;
        }

        // ---------------------------------------------------------------
        // 4. 요금
        // ---------------------------------------------------------------

        void applyFares(List<GtfsFeed.Pattern> patterns) throws IOException {
            Map<String, Integer> prices = new HashMap<>();
            Set<Integer> distinct = new HashSet<>();
            try (GtfsCsv csv = open("fare_attributes.txt", false)) {
                if (csv != null) {
                    while (csv.next()) {
                        String currency = csv.get("currency_type");
                        double price = parseDouble(csv.get("price"));
                        if (csv.get("fare_id").isEmpty() || Double.isNaN(price) || price < 0 || price > 200_000) continue;
                        if (!currency.isEmpty() && !"JPY".equalsIgnoreCase(currency)) continue;
                        prices.put(csv.get("fare_id"), (int) Math.round(price));
                        distinct.add((int) Math.round(price));
                    }
                }
            }
            if (prices.isEmpty()) {
                feed.warnings.add("요금 정보(fare_attributes.txt)가 없어 이 노선들의 요금은 거리 기준 추정으로 나갑니다.");
                return;
            }

            Map<String, Integer> zoneIndex = new HashMap<>();
            int[] stopZone = new int[stopIds.size()];
            for (int i = 0; i < stopZone.length; i++) {
                String zone = stopZones.get(i);
                Integer z = zoneIndex.get(zone);
                if (z == null) {
                    z = zoneIndex.size();
                    zoneIndex.put(zone, z);
                }
                stopZone[i] = z;
            }

            FareMap byPair = new FareMap();                 // (노선, 출발 구역, 도착 구역) 또는 (노선 없음, 출발, 도착)
            int[] routeFlat = new int[routes.size()];
            Arrays.fill(routeFlat, -1);
            int globalFlat = -1;
            int rules = 0;
            int ignored = 0;
            try (GtfsCsv csv = open("fare_rules.txt", false)) {
                if (csv != null) {
                    while (csv.next()) {
                        Integer price = prices.get(csv.get("fare_id"));
                        if (price == null) continue;
                        rules++;
                        String routeId = csv.get("route_id");
                        String origin = csv.get("origin_id");
                        String destination = csv.get("destination_id");
                        int route = -1;
                        if (!routeId.isEmpty()) {
                            Integer r = routeIndex.get(routeId);
                            if (r == null) continue;
                            route = r;
                        }
                        if (origin.isEmpty() && destination.isEmpty()) {
                            if (route >= 0) routeFlat[route] = Math.max(routeFlat[route], price);
                            else globalFlat = Math.max(globalFlat, price);
                            continue;
                        }
                        Integer o = zoneIndex.get(origin);
                        Integer d = zoneIndex.get(destination);
                        if (o == null || d == null) {
                            ignored++;       // 한쪽만 지정했거나, 우리가 뺀 정류장의 구역
                            continue;
                        }
                        byPair.put(pairKey(route, o, d), price);
                    }
                }
            }
            feed.fareRuleCount = rules;
            if (rules == 0 && distinct.size() == 1) globalFlat = distinct.iterator().next();   // 규칙 없이 요금이 하나뿐: 전 노선 균일
            if (byPair.conflicts > 0) feed.warnings.add("같은 구간에 요금이 둘 이상인 규칙 " + byPair.conflicts + "건은 비싼 쪽(현금 성인 요금으로 추정)을 썼습니다.");

            long pairs = 0;
            long known = 0;
            for (GtfsFeed.Pattern pattern : patterns) {
                int route = routeIndex.get(pattern.routeId);
                int n = pattern.stops.length;
                int[] fares = new int[n * (n - 1) / 2];
                int at = 0;
                int same = Integer.MIN_VALUE;
                boolean uniform = true;
                for (int i = 0; i < n - 1; i++) {
                    int from = stopZone[pattern.stops[i]];
                    for (int j = i + 1; j < n; j++) {
                        int to = stopZone[pattern.stops[j]];
                        int fare = byPair.get(pairKey(route, from, to));
                        if (fare < 0) fare = byPair.get(pairKey(-1, from, to));
                        if (fare < 0) fare = routeFlat[route];
                        if (fare < 0) fare = globalFlat;
                        fares[at++] = fare;
                        if (same == Integer.MIN_VALUE) same = fare;
                        else if (same != fare) uniform = false;
                        pairs++;
                        if (fare >= 0) known++;
                    }
                }
                if (uniform) {
                    if (same >= 0) pattern.flatFare = same;
                } else {
                    pattern.fares = fares;
                }
            }
            feed.fareCoveragePercent = pairs == 0 ? 0 : (int) (known * 100 / pairs);
            if (ignored > 0 && feed.fareCoveragePercent < 100) feed.warnings.add("구역을 찾을 수 없는 요금 규칙 " + ignored + "건을 건너뛰었습니다.");
        }

        private static long pairKey(int route, int origin, int destination) {
            return ((long) (route + 1) << 44) | ((long) origin << 22) | destination;
        }

        // ---------------------------------------------------------------
        // 5. 정류장 정리와 한국어 표기
        // ---------------------------------------------------------------

        void finishStops(List<GtfsFeed.Pattern> patterns) throws IOException {
            Map<String, String> kanaByName = new HashMap<>();
            Map<String, String> koreanByName = new HashMap<>();
            Map<String, String> kanaById = new HashMap<>();
            Map<String, String> koreanById = new HashMap<>();
            try (GtfsCsv csv = open("translations.txt", false)) {
                if (csv != null) {
                    boolean modern = csv.has("table_name");
                    while (csv.next()) {
                        String language = (modern ? csv.get("language") : csv.get("lang")).toLowerCase();
                        boolean kana = language.startsWith("ja-h");
                        boolean korean = language.equals("ko") || language.startsWith("ko-");
                        if (!kana && !korean) continue;
                        String translation = csv.get("translation");
                        if (translation.isEmpty()) continue;
                        if (modern) {
                            if (!"stops".equals(csv.get("table_name")) || !"stop_name".equals(csv.get("field_name"))) continue;
                            String recordId = csv.get("record_id");
                            String fieldValue = csv.get("field_value");
                            if (!recordId.isEmpty()) (kana ? kanaById : koreanById).put(recordId, translation);
                            if (!fieldValue.isEmpty()) (kana ? kanaByName : koreanByName).put(fieldValue, translation);
                        } else {
                            String original = csv.get("trans_id");
                            if (!original.isEmpty()) (kana ? kanaByName : koreanByName).put(original, translation);
                        }
                    }
                }
            }

            // 실제로 쓰이는 정류장만 남기고 번호를 다시 매긴다
            int[] newIndex = new int[stopIds.size()];
            Arrays.fill(newIndex, -1);
            for (GtfsFeed.Pattern pattern : patterns) {
                int[] remapped = new int[pattern.stops.length];
                for (int k = 0; k < remapped.length; k++) {
                    int old = pattern.stops[k];
                    if (newIndex[old] < 0) {
                        GtfsFeed.Stop stop = new GtfsFeed.Stop();
                        stop.index = feed.stops.size();
                        stop.id = stopIds.get(old);
                        stop.name = stopNames.get(old);
                        stop.latitude = stopCoords.get(old)[0];
                        stop.longitude = stopCoords.get(old)[1];
                        stop.nameKo = korean(stop, kanaByName, koreanByName, kanaById, koreanById);
                        newIndex[old] = stop.index;
                        feed.stops.add(stop);
                    }
                    remapped[k] = newIndex[old];
                }
                pattern.stops = remapped;
            }
        }

        private static String korean(GtfsFeed.Stop stop, Map<String, String> kanaByName, Map<String, String> koreanByName,
                                     Map<String, String> kanaById, Map<String, String> koreanById) {
            String korean = koreanById.get(stop.id);
            if (korean == null) korean = koreanByName.get(stop.name);
            if (korean != null && hasHangul(korean) && korean.length() <= 80) return korean.trim();
            String kana = kanaById.get(stop.id);
            if (kana == null) kana = kanaByName.get(stop.name);
            String converted = KanaHangul.convert(kana);
            return converted != null && hasHangul(converted) && converted.length() <= 80 ? converted : null;
        }
    }

    // =====================================================================
    // 공용 변환
    // =====================================================================

    /** GTFS route_type → 교통수단 종류 */
    static RailCategory categoryOf(int routeType, String agency) {
        if (routeType == 0 || routeType == 5 || (routeType >= 900 && routeType < 1000)) return RailCategory.TRAM;
        if (routeType == 1 || (routeType >= 400 && routeType < 500)) return RailCategory.SUBWAY;
        if (routeType == 2 || (routeType >= 100 && routeType < 200)) {
            return agency != null && agency.contains("旅客鉄道") ? RailCategory.JR : RailCategory.PRIVATE;
        }
        if (routeType == 4 || (routeType >= 1000 && routeType < 1100) || routeType == 1200) return RailCategory.FERRY;
        if (routeType == 6 || routeType == 7 || (routeType >= 1300 && routeType < 1500)) return RailCategory.CABLE;
        if (routeType == 12) return RailCategory.GUIDED;
        return RailCategory.BUS;   // 3(버스), 11(트롤리버스), 200·700번대(버스), 1500번대(합승 택시) 등
    }

    /** 전국 철도망(국토수치정보)에 들어 있는 종류: 철도·지하철·모노레일 */
    static boolean isRailNetworkType(int routeType) {
        RailCategory category = categoryOf(routeType, "");
        return category == RailCategory.SUBWAY || category == RailCategory.PRIVATE || category == RailCategory.JR || category == RailCategory.GUIDED;
    }

    static boolean hasHangul(String s) {
        return s != null && s.chars().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3);
    }

    /** "8:05:00", "25:10:00" → 초. 비었거나 형식이 틀리면 -1 */
    static int parseTime(String text) {
        if (text == null || text.isEmpty()) return -1;
        String[] parts = text.split(":");
        if (parts.length < 2) return -1;
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            int s = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
            if (h < 0 || h > 47 || m < 0 || m > 59 || s < 0 || s > 59) return -1;
            return h * 3600 + m * 60 + s;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "20261011", "2026-10-11", "2026/10/11" */
    static LocalDate parseDate(String text) {
        if (text == null) return null;
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.length() != 8) return null;
        try {
            return LocalDate.of(Integer.parseInt(digits.substring(0, 4)), Integer.parseInt(digits.substring(4, 6)),
                    Integer.parseInt(digits.substring(6, 8)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int parseInt(String text, int fallback) {
        if (text == null || text.isEmpty()) return fallback;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String text) {
        if (text == null || text.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
