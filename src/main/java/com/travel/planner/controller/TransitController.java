package com.travel.planner.controller;

import com.travel.planner.dto.GtfsSourceDto;
import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.entity.TransitFeed;
import com.travel.planner.repository.TransitFeedRepository;
import com.travel.planner.service.GtfsImportService;
import com.travel.planner.service.GtfsStore;
import com.travel.planner.service.RailNetworkImportService;
import com.travel.planner.service.RailRouteService;
import com.travel.planner.service.TransitNameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "4. 교통 API", description = "전국 철도망(국토교통성 국토수치정보)과 버스 등 GTFS 공개 자료 기반 경로·요금")
public class TransitController {

    private final RailRouteService railRouteService;
    private final RailNetworkImportService importService;
    private final TransitNameService nameService;
    private final GtfsImportService gtfsImportService;
    private final GtfsStore gtfsStore;
    private final TransitFeedRepository feedRepository;

    @GetMapping("/api/v1/transit/route")
    @Operation(summary = "두 지점 사이의 이동 방법·요금 추정",
            description = "도보 / 대중교통(철도·버스, 환승 포함) / 노선 미확인(거리 기준) 중 하나로 돌려줍니다. 개별 출발 시각이 아니라 평균 소요 시간과 운행 간격으로 계산한 추정값입니다. "
                    + "minutes 를 주면 그 시간을 일정상의 이동 시간으로 보고 판단합니다. date(yyyy-MM-dd)를 주면 그 요일의 버스 운행 횟수와 계절 노선의 운행 기간을, "
                    + "time(HH:mm)을 주면 첫차·막차 밖인지를 함께 봅니다.")
    public ResponseEntity<?> route(@RequestParam double fromLat, @RequestParam double fromLng,
                                   @RequestParam double toLat, @RequestParam double toLng,
                                   @RequestParam(defaultValue = "출발지") String fromName,
                                   @RequestParam(defaultValue = "도착지") String toName,
                                   @RequestParam(defaultValue = "도보 및 대중교통") String transportation,
                                   @RequestParam(required = false) Integer minutes,
                                   @RequestParam(required = false) String date,
                                   @RequestParam(required = false) String time) {
        LocalDate day = null;
        Integer depart = null;
        try {
            if (date != null && !date.isBlank()) day = LocalDate.parse(date.trim());
            if (time != null && !time.isBlank()) {
                String[] parts = time.trim().split(":");
                depart = Integer.parseInt(parts[0]) * 60 + (parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
            }
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "date 는 yyyy-MM-dd, time 은 HH:mm 형식으로 보내 주세요."));
        }
        int schedule = minutes != null ? minutes
                : com.travel.planner.util.TravelTimeEstimator.minutes(fromLat, fromLng, toLat, toLng, transportation);
        TransitLegDto leg = railRouteService.leg(fromLat, fromLng, fromName, toLat, toLng, toName, transportation, schedule, day, depart);
        if (leg == null) return ResponseEntity.ok(Map.of("message", "같은 자리이거나 경로를 계산할 수 없습니다."));
        return ResponseEntity.ok(leg);
    }

    @GetMapping("/api/v1/transit/sources")
    @Operation(summary = "교통 자료 출처",
            description = "경로·요금 계산에 쓰는 공개 자료의 목록과 이용 조건입니다. 대부분 출처 표시(CC BY)가 조건이므로 앱의 '정보' 화면 등에 표시해 주세요.")
    public List<Map<String, Object>> sources() {
        List<Map<String, Object>> list = new ArrayList<>();
        if (railRouteService.isRailLoaded()) {
            Map<String, Object> rail = new LinkedHashMap<>();
            rail.put("name", "국토수치정보 철도 데이터 (国土数値情報 鉄道データ)");
            rail.put("provider", "国土交通省");
            rail.put("license", "国土数値情報 이용 약관 (출처 표시)");
            list.add(rail);
        }
        for (TransitFeed feed : feeds()) {
            if (feed.getImportedAt() == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", feed.getName());
            item.put("provider", feed.getAgencies());
            item.put("license", feed.getLicense());
            item.put("validTo", feed.getValidTo());
            list.add(item);
        }
        return list;
    }

    @PostMapping("/api/v1/admin/transit/import-rail-network")
    @Operation(summary = "전국 철도망 가져오기 (백그라운드)",
            description = "data 폴더의 국토수치정보 철도 zip(N02-xx_GML.zip)을 읽어 역과 역간 연결을 DB 에 넣습니다. 기존 철도망은 통째로 바뀝니다. "
                    + "path 로 다른 위치의 파일을 지정할 수 있습니다. 진행 결과는 /api/v1/admin/transit/status 와 서버 로그의 [철도망 가져오기] 줄로 확인합니다.")
    public ResponseEntity<String> importRailNetwork(@RequestParam(required = false) String path) {
        if (importService.isRunning()) return ResponseEntity.badRequest().body("이미 철도망 가져오기가 실행 중입니다.");
        Path file;
        try {
            file = importService.resolveFile(path);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        importService.importAsync(file);
        return ResponseEntity.ok("철도망 가져오기를 시작했습니다: " + file.toAbsolutePath());
    }

    @PostMapping("/api/v1/admin/transit/translate-names")
    @Operation(summary = "역·노선·회사 이름 한국어 표기 채우기 (백그라운드)",
            description = "철도망의 일본어 이름 가운데 아직 번역되지 않은 것을 80개씩 AI 에 보내 한국어 표기를 저장합니다. 철도망을 가져온 뒤에 실행합니다.")
    public ResponseEntity<String> translateNames() {
        if (nameService.isTranslating()) return ResponseEntity.badRequest().body("이미 이름 번역이 실행 중입니다.");
        if (railRouteService.stationCount() == 0) return ResponseEntity.badRequest().body("철도망이 비어 있습니다. 먼저 import-rail-network 를 실행해 주세요.");
        nameService.translateMissing();
        return ResponseEntity.ok("이름 번역을 시작했습니다. 진행 상황은 서버 로그의 [교통 이름 번역] 줄로 확인하세요.");
    }

    @PostMapping("/api/v1/admin/transit/translate-names/stop")
    @Operation(summary = "실행 중인 이름 번역 중지")
    public ResponseEntity<String> stopTranslateNames() {
        if (!nameService.isTranslating()) return ResponseEntity.ok("현재 실행 중인 이름 번역이 없습니다.");
        nameService.stopTranslating();
        return ResponseEntity.ok("이름 번역 중지 명령을 전송했습니다. 현재 묶음이 끝나면 중지됩니다.");
    }

    // =====================================================================
    // GTFS 피드 (버스·노면전차·배)
    // =====================================================================

    @GetMapping("/api/v1/admin/transit/gtfs-catalog")
    @Operation(summary = "가져올 수 있는 GTFS 피드 기본 목록",
            description = "자주 쓰는 도시의 버스·노면전차 피드 목록입니다. imported 는 이미 가져왔는지, url 이 'acl:consumerKey=' 로 끝나면 ODPT 개발자 키가 필요한 피드입니다. "
                    + "city 로 도시 이름(한국어)을 주면 그 도시의 것만 보여 줍니다.")
    public List<GtfsSourceDto> gtfsCatalog(@RequestParam(required = false) String city) {
        return gtfsImportService.select(null, city);
    }

    @PostMapping("/api/v1/admin/transit/import-gtfs")
    @Operation(summary = "GTFS 피드 하나 가져오기 (백그라운드)",
            description = "url(zip 주소)에서 내려받거나 path(서버의 파일. data/gtfs 기준 상대 경로 가능)에서 읽어 DB 에 넣습니다. "
                    + "key 는 이 피드에 붙일 짧은 이름(영문·숫자)이고, 같은 key 로 다시 가져오면 그 피드만 통째로 바뀝니다. license 에는 출처 표시 문구를 적어 둡니다. "
                    + "피드 안의 철도·지하철 노선은 전국 철도망과 겹치므로 기본으로는 가져오지 않습니다 (노선이 하나뿐인 모노레일 등에서만 includeRail=true).")
    public ResponseEntity<String> importGtfs(@RequestParam String key,
                                             @RequestParam(required = false) String url,
                                             @RequestParam(required = false) String path,
                                             @RequestParam(required = false) String name,
                                             @RequestParam(required = false) String license,
                                             @RequestParam(defaultValue = "false") boolean includeRail) {
        if ((url == null || url.isBlank()) && (path == null || path.isBlank())) {
            return ResponseEntity.badRequest().body("url 이나 path 중 하나가 필요합니다.");
        }
        if (gtfsImportService.isRunning()) return ResponseEntity.badRequest().body("이미 GTFS 가져오기가 실행 중입니다.");
        GtfsSourceDto source = new GtfsSourceDto();
        source.setKey(key);
        source.setUrl(url);
        source.setPath(path);
        source.setName(name);
        source.setLicense(license);
        source.setIncludeRail(includeRail);
        gtfsImportService.importAsync(List.of(source));
        return ResponseEntity.ok("GTFS 가져오기를 시작했습니다: " + key + ". 결과는 /api/v1/admin/transit/feeds 와 서버 로그의 [GTFS 가져오기] 줄로 확인하세요.");
    }

    @PostMapping("/api/v1/admin/transit/import-gtfs-catalog")
    @Operation(summary = "기본 목록의 GTFS 피드 가져오기 (백그라운드)",
            description = "keys(쉼표로 구분한 피드 키) 또는 city(도시 이름)로 고른 피드를 차례로 내려받아 넣습니다. 둘 다 없으면 all=true 일 때만 목록 전체를 가져옵니다. "
                    + "ODPT 키가 없으면 키가 필요한 피드는 실패로 기록되고 나머지는 계속 진행합니다.")
    public ResponseEntity<String> importGtfsCatalog(@RequestParam(required = false) String keys,
                                                    @RequestParam(required = false) String city,
                                                    @RequestParam(defaultValue = "false") boolean all) {
        boolean chosen = (keys != null && !keys.isBlank()) || (city != null && !city.isBlank());
        if (!chosen && !all) return ResponseEntity.badRequest().body("keys 나 city 로 가져올 피드를 골라 주세요. 목록 전체는 all=true 로 요청합니다.");
        List<GtfsSourceDto> sources = gtfsImportService.select(keys, city);
        if (sources.isEmpty()) return ResponseEntity.badRequest().body("조건에 맞는 피드가 기본 목록에 없습니다. /api/v1/admin/transit/gtfs-catalog 로 목록을 확인해 주세요.");
        if (gtfsImportService.isRunning()) return ResponseEntity.badRequest().body("이미 GTFS 가져오기가 실행 중입니다.");
        gtfsImportService.importAsync(sources);
        return ResponseEntity.ok("GTFS 피드 " + sources.size() + "개 가져오기를 시작했습니다. 결과는 /api/v1/admin/transit/feeds 와 서버 로그의 [GTFS 가져오기] 줄로 확인하세요.");
    }

    @PostMapping("/api/v1/admin/transit/refresh-gtfs")
    @Operation(summary = "등록된 GTFS 피드 모두 다시 가져오기 (백그라운드)",
            description = "이미 가져온 피드를 같은 주소에서 다시 내려받아 최신 시각표·요금으로 바꿉니다. 시각표 개정 뒤나 유효 기간이 지났을 때 실행합니다.")
    public ResponseEntity<String> refreshGtfs() {
        List<GtfsSourceDto> sources = gtfsImportService.registered();
        if (sources.isEmpty()) return ResponseEntity.badRequest().body("다시 가져올 피드가 없습니다.");
        if (gtfsImportService.isRunning()) return ResponseEntity.badRequest().body("이미 GTFS 가져오기가 실행 중입니다.");
        gtfsImportService.importAsync(sources);
        return ResponseEntity.ok("GTFS 피드 " + sources.size() + "개를 다시 가져옵니다.");
    }

    @PostMapping("/api/v1/admin/transit/import-gtfs/stop")
    @Operation(summary = "실행 중인 GTFS 가져오기 중지", description = "지금 처리 중인 피드까지만 끝내고 멈춥니다.")
    public ResponseEntity<String> stopGtfsImport() {
        if (!gtfsImportService.isRunning()) return ResponseEntity.ok("현재 실행 중인 GTFS 가져오기가 없습니다.");
        gtfsImportService.requestStop();
        return ResponseEntity.ok("중지 명령을 전송했습니다. 지금 처리 중인 피드가 끝나면 멈춥니다.");
    }

    @GetMapping("/api/v1/admin/transit/feeds")
    @Operation(summary = "등록된 GTFS 피드 목록", description = "피드별 정류장·노선 수, 유효 기간, 요금 확인 비율, 마지막 가져오기 결과")
    public List<TransitFeed> listFeeds() {
        return feeds();
    }

    @DeleteMapping("/api/v1/admin/transit/feeds/{feedKey}")
    @Operation(summary = "GTFS 피드 삭제", description = "그 피드의 정류장·노선을 DB 에서 지웁니다.")
    public ResponseEntity<String> deleteFeed(@PathVariable String feedKey) {
        if (gtfsImportService.isRunning()) return ResponseEntity.badRequest().body("GTFS 가져오기가 실행 중입니다. 끝난 뒤에 삭제해 주세요.");
        boolean existed = gtfsStore.deleteFeed(feedKey);
        railRouteService.invalidate();
        return existed ? ResponseEntity.ok("피드 '" + feedKey + "' 를 삭제했습니다.") : ResponseEntity.badRequest().body("그런 피드가 없습니다: " + feedKey);
    }

    private List<TransitFeed> feeds() {
        try {
            List<TransitFeed> list = new ArrayList<>(feedRepository.findAll());
            list.sort(Comparator.comparing(TransitFeed::getFeedKey, Comparator.nullsLast(Comparator.naturalOrder())));
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @GetMapping("/api/v1/admin/transit/status")
    @Operation(summary = "교통 데이터 상태", description = "철도망 역·연결 수, GTFS 정류장·노선 패턴·피드 수, 번역된 이름 수, 가져오기·번역 작업의 마지막 결과")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("stations", railRouteService.stationCount());
        status.put("links", railRouteService.linkCount());
        status.put("busStops", railRouteService.stopCount());
        status.put("busPatterns", railRouteService.patternCount());
        status.put("gtfsFeeds", feeds().size());
        status.put("odptKeyConfigured", gtfsImportService.hasOdptKey());
        status.put("translatedNames", nameService.translatedCount());
        status.put("importRunning", importService.isRunning());
        status.put("importMessage", importService.lastMessage());
        status.put("gtfsImportRunning", gtfsImportService.isRunning());
        status.put("gtfsImportMessage", gtfsImportService.lastMessage());
        status.put("translateRunning", nameService.isTranslating());
        status.put("translateMessage", nameService.lastMessage());
        return status;
    }
}
