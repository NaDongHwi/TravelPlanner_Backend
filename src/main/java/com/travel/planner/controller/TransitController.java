package com.travel.planner.controller;

import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.service.RailNetworkImportService;
import com.travel.planner.service.RailRouteService;
import com.travel.planner.service.TransitNameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "4. 교통 API", description = "전국 철도망 기반 경로·요금 추정 (국토교통성 국토수치정보)")
public class TransitController {

    private final RailRouteService railRouteService;
    private final RailNetworkImportService importService;
    private final TransitNameService nameService;

    @GetMapping("/api/v1/transit/route")
    @Operation(summary = "두 지점 사이의 이동 방법·요금 추정",
            description = "도보 / 철도(환승 포함) / 노선 미확인(거리 기준) 중 하나로 돌려줍니다. 열차 시각이 아니라 거리와 평균 속도로 계산한 추정값입니다. "
                    + "minutes 를 주면 그 시간을 일정상의 이동 시간으로 보고 판단합니다.")
    public ResponseEntity<?> route(@RequestParam double fromLat, @RequestParam double fromLng,
                                   @RequestParam double toLat, @RequestParam double toLng,
                                   @RequestParam(defaultValue = "출발지") String fromName,
                                   @RequestParam(defaultValue = "도착지") String toName,
                                   @RequestParam(defaultValue = "도보 및 대중교통") String transportation,
                                   @RequestParam(required = false) Integer minutes) {
        int schedule = minutes != null ? minutes
                : com.travel.planner.util.TravelTimeEstimator.minutes(fromLat, fromLng, toLat, toLng, transportation);
        TransitLegDto leg = railRouteService.leg(fromLat, fromLng, fromName, toLat, toLng, toName, transportation, schedule);
        if (leg == null) return ResponseEntity.ok(Map.of("message", "같은 자리이거나 경로를 계산할 수 없습니다."));
        return ResponseEntity.ok(leg);
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

    @GetMapping("/api/v1/admin/transit/status")
    @Operation(summary = "교통 데이터 상태", description = "철도망 역·연결 수, 번역된 이름 수, 가져오기·번역 작업의 마지막 결과")
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("stations", railRouteService.stationCount());
        status.put("links", railRouteService.linkCount());
        status.put("translatedNames", nameService.translatedCount());
        status.put("importRunning", importService.isRunning());
        status.put("importMessage", importService.lastMessage());
        status.put("translateRunning", nameService.isTranslating());
        status.put("translateMessage", nameService.lastMessage());
        return status;
    }
}
