package com.travel.planner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.dto.GtfsSourceDto;
import com.travel.planner.entity.TransitFeed;
import com.travel.planner.repository.TransitFeedRepository;
import com.travel.planner.transit.GtfsFeed;
import com.travel.planner.transit.GtfsReader;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * GTFS 피드(버스·노면전차·배 등의 노선·시각표·요금 공개 자료)를 내려받아 DB 에 넣는다.
 *
 * - 주소(zip)에서 내려받거나, 서버에 둔 파일을 읽는다. 내려받은 파일은 data/gtfs 폴더에 "키.zip" 으로 남는다.
 * - 같은 키로 다시 가져오면 그 피드의 자료만 통째로 바뀐다. 시각표가 개정되면 다시 가져오면 된다.
 * - 자주 쓰는 피드는 기본 목록(resources/transit/gtfs-feeds.json)에 있고, 목록에 없는 피드도 주소만 알면 가져올 수 있다.
 * - 공공교통 오픈데이터 센터(ODPT)의 일부 피드는 개발자 키가 필요하다 (transit.odpt.consumer-key).
 */
@Service
@RequiredArgsConstructor
public class GtfsImportService {

    private static final long MAX_DOWNLOAD_BYTES = 500L * 1024 * 1024;
    private static final String ODPT_KEY_PARAM = "acl:consumerKey=";

    private final GtfsStore store;
    private final RailRouteService routeService;
    private final TransitFeedRepository feedRepository;

    @Value("${transit.gtfs.data-dir:./data/gtfs}")
    private String dataDir = "./data/gtfs";

    @Value("${transit.odpt.consumer-key:}")
    private String odptKey = "";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean stopRequested;
    private volatile String lastMessage = "아직 가져온 적이 없습니다.";

    public boolean isRunning() { return running.get(); }
    public String lastMessage() { return lastMessage; }
    public void requestStop() { stopRequested = true; }
    public boolean hasOdptKey() { return odptKey != null && !odptKey.isBlank(); }

    // =====================================================================
    // 기본 목록
    // =====================================================================

    /** 기본 목록 전체. 이미 가져온 피드는 imported = true */
    public List<GtfsSourceDto> catalog() {
        List<GtfsSourceDto> entries;
        try (InputStream in = GtfsImportService.class.getResourceAsStream("/transit/gtfs-feeds.json")) {
            if (in == null) return new ArrayList<>();
            entries = new ObjectMapper().readValue(in, new TypeReference<List<GtfsSourceDto>>() {});
        } catch (IOException e) {
            System.out.println("[GTFS] 기본 목록을 읽지 못했습니다: " + e.getMessage());
            return new ArrayList<>();
        }
        Set<String> imported;
        try {
            imported = feedRepository.findAll().stream()
                    .filter(f -> f.getImportedAt() != null).map(TransitFeed::getFeedKey).collect(Collectors.toSet());
        } catch (Exception e) {
            imported = Set.of();
        }
        for (GtfsSourceDto entry : entries) entry.setImported(imported.contains(entry.getKey()));
        return entries;
    }

    /** 기본 목록에서 고른다: keys(쉼표 구분)가 있으면 그 키들, 없으면 city 가 들어간 것, 둘 다 없으면 전부. */
    public List<GtfsSourceDto> select(String keys, String city) {
        List<GtfsSourceDto> all = catalog();
        if (keys != null && !keys.isBlank()) {
            Set<String> wanted = java.util.Arrays.stream(keys.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
            return all.stream().filter(e -> wanted.contains(e.getKey())).collect(Collectors.toList());
        }
        if (city != null && !city.isBlank()) {
            String c = city.trim();
            return all.stream().filter(e -> e.getCities() != null && e.getCities().stream().anyMatch(x -> x.contains(c) || c.contains(x)))
                    .collect(Collectors.toList());
        }
        return all;
    }

    /** 이미 등록된 피드 가운데 주소로 받은 것 (다시 가져오기 대상) */
    public List<GtfsSourceDto> registered() {
        List<GtfsSourceDto> sources = new ArrayList<>();
        for (TransitFeed feed : feedRepository.findAll()) {
            if (feed.getUrl() == null || !feed.getUrl().startsWith("http")) continue;
            GtfsSourceDto source = new GtfsSourceDto();
            source.setKey(feed.getFeedKey());
            source.setName(feed.getName());
            source.setUrl(feed.getUrl());
            source.setLicense(feed.getLicense());
            source.setIncludeRail(feed.getIncludeRail());
            sources.add(source);
        }
        return sources;
    }

    // =====================================================================
    // 가져오기
    // =====================================================================

    @Async("adminTaskExecutor")
    public void importAsync(List<GtfsSourceDto> sources) {
        if (!running.compareAndSet(false, true)) return;
        stopRequested = false;
        int ok = 0;
        List<String> failed = new ArrayList<>();
        try {
            System.out.println("[GTFS 가져오기] 피드 " + sources.size() + "개를 차례로 가져옵니다.");
            for (GtfsSourceDto source : sources) {
                if (stopRequested) break;
                try {
                    String summary = importOne(source);
                    ok++;
                    System.out.println("[GTFS 가져오기] 완료 " + summary);
                } catch (Exception e) {
                    failed.add(source.getKey() + "(" + e.getMessage() + ")");
                    System.out.println("[GTFS 가져오기] 실패 " + source.getKey() + ": " + e.getMessage());
                }
            }
            lastMessage = "피드 " + ok + "개를 가져왔습니다." + (failed.isEmpty() ? "" : " 실패 " + failed.size() + "개: " + String.join(", ", failed))
                    + (stopRequested ? " (중지 요청으로 나머지는 건너뛰었습니다)" : "");
        } catch (Exception e) {
            lastMessage = "가져오기 중 오류: " + e.getMessage();
        } finally {
            if (ok > 0) routeService.invalidate();
            running.set(false);
            System.out.println("[GTFS 가져오기 종료] " + lastMessage);
        }
    }

    /**
     * 피드 하나를 내려받아(또는 파일에서 읽어) DB 에 넣는다. 그래프 갱신(invalidate)은 호출한 쪽에서 한다.
     * @return 결과 요약 문장
     */
    public String importOne(GtfsSourceDto source) throws IOException {
        String key = normalizeKey(source.getKey());
        String url = source.getUrl() == null ? null : source.getUrl().trim();
        try {
            long started = System.currentTimeMillis();
            Path file;
            String origin;
            if (source.getPath() != null && !source.getPath().isBlank()) {
                file = resolveLocal(source.getPath().trim());
                origin = file.toString();
            } else if (url != null && !url.isEmpty()) {
                file = download(key, url);
                origin = url;       // 키가 붙지 않은 원래 주소를 남긴다
            } else {
                throw new IOException("주소(url)나 파일 경로(path)가 필요합니다.");
            }
            GtfsFeed feed = GtfsReader.read(file, LocalDate.now(ZoneId.of("Asia/Tokyo")), Boolean.TRUE.equals(source.getIncludeRail()));
            TransitFeed saved = store.replaceFeed(key, source.getName(), origin, source.getLicense(), feed);
            return String.format("%s (%s): 정류장 %d개, 노선 패턴 %d개, 요금 확인 %d%%, 유효 %s ~ %s%s (%d초)%s",
                    key, saved.getName(), feed.stops.size(), feed.patterns.size(), feed.fareCoveragePercent, feed.validFrom, feed.validTo,
                    feed.expired ? " [유효 기간 지남]" : "", (System.currentTimeMillis() - started) / 1000,
                    feed.warnings.isEmpty() ? "" : " — " + String.join(" ", feed.warnings));
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (hasOdptKey()) reason = reason.replace(odptKey.trim(), "***");   // 오류 문장에 키가 섞여 나가지 않게
            try {
                store.markFailed(key, source.getName(), url, source.getLicense(), reason);
            } catch (Exception ignored) {
                // 실패 기록까지 실패해도 원래 오류를 알리는 것이 먼저다
            }
            throw new IOException(reason, e);
        }
    }

    /** 키는 영문 소문자·숫자·-·_ 만 쓴다 (파일 이름과 DB 키로 쓰인다). */
    public static String normalizeKey(String key) throws IOException {
        if (key == null) throw new IOException("피드 키(key)가 필요합니다.");
        String k = key.trim().toLowerCase().replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (k.isEmpty()) throw new IOException("피드 키(key)는 영문·숫자로 지어 주세요.");
        return k.length() > 60 ? k.substring(0, 60) : k;
    }

    private Path resolveLocal(String path) throws IOException {
        Path given = Path.of(path);
        if (!given.isAbsolute() && !Files.exists(given)) given = Path.of(dataDir).resolve(path);
        if (!Files.isRegularFile(given)) throw new IOException("파일이 없습니다: " + given.toAbsolutePath());
        return given;
    }

    /** zip 을 data/gtfs/키.zip 으로 내려받는다. 다 받은 뒤에 이름을 바꾸므로, 받다가 실패해도 이전 파일은 남는다. */
    Path download(String key, String url) throws IOException {
        URI uri = toUri(withOdptKey(url));
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            throw new IOException("http(s) 주소만 내려받을 수 있습니다.");
        }
        Path dir = Path.of(dataDir);
        Files.createDirectories(dir);
        Path target = dir.resolve(key + ".zip");
        Path temp = dir.resolve(key + ".zip.part");

        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5))
                .header("User-Agent", "Mozilla/5.0 (compatible; TravelPlannerBackend/1.0; GTFS import)")
                .header("Accept", "application/zip, application/octet-stream, */*").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                String hint = response.statusCode() == 401 || response.statusCode() == 403
                        ? (url.contains("odpt.org") ? " (ODPT 키가 맞는지 확인해 주세요)" : " (서버가 자동 내려받기를 막고 있습니다. 브라우저로 받아 data/gtfs 에 넣고 path 로 가져올 수 있습니다)")
                        : "";
                throw new IOException("내려받기 실패: HTTP " + response.statusCode() + hint);
            }
            try (InputStream in = response.body(); java.io.OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[1 << 16];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_DOWNLOAD_BYTES) throw new IOException("파일이 너무 큽니다 (" + MAX_DOWNLOAD_BYTES / 1024 / 1024 + "MB 초과).");
                    out.write(buffer, 0, read);
                }
            }
            if (!looksLikeZip(temp)) throw new IOException("내려받은 것이 zip 파일이 아닙니다 (주소가 안내 페이지이거나 로그인이 필요한 주소일 수 있습니다).");
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("내려받기가 중단되었습니다.");
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean looksLikeZip(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.read() == 'P' && in.read() == 'K';
        }
    }

    /** ODPT 주소의 비어 있는 키 자리에 설정의 키를 넣는다. */
    String withOdptKey(String url) throws IOException {
        int at = url.indexOf(ODPT_KEY_PARAM);
        if (at < 0) return url;
        int valueStart = at + ODPT_KEY_PARAM.length();
        boolean empty = valueStart >= url.length() || url.charAt(valueStart) == '&';
        if (!empty) return url;
        if (!hasOdptKey()) {
            throw new IOException("이 피드는 공공교통 오픈데이터 센터(ODPT) 개발자 키가 필요합니다. https://developer.odpt.org 에서 무료로 등록한 뒤 "
                    + "설정 transit.odpt.consumer-key 에 넣어 주세요.");
        }
        return url.substring(0, valueStart) + odptKey.trim() + url.substring(valueStart);
    }

    /** 공백·일본어 등 주소에 그대로 쓸 수 없는 글자를 %XX 로 바꾼다 (이미 %XX 인 부분은 그대로 둔다). */
    static URI toUri(String url) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (byte b : url.trim().getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean safe = c > 0x20 && c < 0x7F && "\"<>\\^`{|}".indexOf(c) < 0;
            if (safe) sb.append((char) c);
            else sb.append('%').append(String.format("%02X", c));
        }
        try {
            return URI.create(sb.toString());
        } catch (IllegalArgumentException e) {
            throw new IOException("주소 형식이 올바르지 않습니다.");
        }
    }
}
