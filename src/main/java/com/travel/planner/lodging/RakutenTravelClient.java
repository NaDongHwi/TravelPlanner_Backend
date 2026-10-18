package com.travel.planner.lodging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 라쿠텐 트래블 API 호출기.
 *  - 키는 application-secret.yml 의 rakuten.application-id / access-key / referer 에서 읽는다. 로그에 남기지 않는다.
 *  - 라쿠텐(2026년 개편 이후)은 요청의 Referer·Origin 이 앱에 등록한 "허용 웹사이트"와 맞아야 받아 준다.
 *    서버에서 부르므로 등록한 주소를 두 머리말에 직접 붙인다. 없으면 403 REQUEST_CONTEXT_BODY_HTTP_REFERRER_MISSING.
 *  - Spring 의 기본 RestTemplate(HttpURLConnection)은 Origin 머리말을 몰래 빼 버리므로, Java 내장 HttpClient 로 직접 보낸다.
 *  - 짧은 시간에 몰아서 부르면 일정 시간 막히므로, 호출 사이를 최소 1초 띄운다.
 *  - 값은 하나씩 인코딩한다 ("+roomCharge" 의 '+' 가 공백으로 읽히지 않도록).
 */
@Component
public class RakutenTravelClient implements RakutenTravelApi {

    private static final long MIN_GAP_MS = 1_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Value("${rakuten.application-id:}")
    private String applicationId;
    @Value("${rakuten.access-key:}")
    private String accessKey;
    @Value("${rakuten.referer:}")
    private String referer;
    @Value("${rakuten.affiliate-id:}")
    private String affiliateId;
    @Value("${rakuten.simple-hotel-url:https://openapi.rakuten.co.jp/engine/api/Travel/SimpleHotelSearch/20170426}")
    private String simpleUrl;
    @Value("${rakuten.vacant-hotel-url:https://openapi.rakuten.co.jp/engine/api/Travel/VacantHotelSearch/20170426}")
    private String vacantUrl;

    private long lastCall;

    @Override
    public boolean configured() {
        return notBlank(applicationId) && notBlank(accessKey) && notBlank(referer);
    }

    @Override
    public String simpleSearch(double latitude, double longitude, double radiusKm) {
        Map<String, Object> q = base();
        q.put("latitude", latitude);
        q.put("longitude", longitude);
        q.put("datumType", 1);
        q.put("searchRadius", String.format(Locale.ROOT, "%.1f", Math.max(0.1, Math.min(3.0, radiusKm))));
        q.put("hits", 30);
        return call(simpleUrl, q, "숙소 찾기");
    }

    @Override
    public String vacantSearch(long hotelNo, LocalDate checkIn, LocalDate checkOut, int adults, int rooms) {
        Map<String, Object> q = base();
        q.put("hotelNo", hotelNo);
        q.put("checkinDate", checkIn.toString());
        q.put("checkoutDate", checkOut.toString());
        q.put("adultNum", Math.max(1, Math.min(10, adults)));
        q.put("roomNum", Math.max(1, Math.min(10, rooms)));
        q.put("searchPattern", 1);
        q.put("sort", "+roomCharge");
        q.put("hits", 30);
        q.put("datumType", 1);
        return call(vacantUrl, q, "빈방 요금");
    }

    private Map<String, Object> base() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("applicationId", applicationId);
        q.put("accessKey", accessKey);
        q.put("format", "json");
        q.put("formatVersion", 2);
        if (notBlank(affiliateId)) q.put("affiliateId", affiliateId);
        return q;
    }

    public static URI uri(String url, Map<String, Object> query) {
        StringBuilder sb = new StringBuilder(url).append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, Object> e : query.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        return URI.create(sb.toString());
    }

    private String call(String url, Map<String, Object> query, String what) {
        if (!configured()) return null;
        waitTurn();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri(url, query))
                    .timeout(Duration.ofSeconds(15))
                    .header("Referer", refererValue(referer))
                    .header("Origin", originOf(referer))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200) return response.body();
            if (status == 404) return "{\"hotels\":[]}";    // 찾은 것이 없음 (빈방 없음 포함)
            String error = RakutenHotels.error(response.body());
            System.out.println("[숙박비] 라쿠텐 " + what + " 실패: HTTP " + status + (error != null ? " " + error : ""));
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.out.println("[숙박비] 라쿠텐 " + what + " 실패: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Referer: 등록한 주소 그대로 (끝에 / 가 없으면 붙인다) */
    static String refererValue(String registered) {
        String r = registered.trim();
        return r.endsWith("/") ? r : r + "/";
    }

    /** Origin: 등록한 주소의 "https://도메인" 부분 (예: https://github.com/계정/저장소 → https://github.com) */
    static String originOf(String registered) {
        URI u = URI.create(registered.trim());
        return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    private synchronized void waitTurn() {
        long wait = lastCall + MIN_GAP_MS - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastCall = System.currentTimeMillis();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
