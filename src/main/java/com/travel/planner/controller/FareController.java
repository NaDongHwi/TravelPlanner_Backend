package com.travel.planner.controller;

import com.travel.planner.dto.FarePairDto;
import com.travel.planner.dto.FareTableDto;
import com.travel.planner.dto.LineKmDto;
import com.travel.planner.dto.TransportPassDto;
import com.travel.planner.service.FareDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 확인해서 등록하는 요금 자료(운영사 요금표·역 쌍 요금·신칸센 영업킬로)와 교통 패스.
 * 기본 자료는 서버가 켜질 때 자동으로 들어가고, 여기서는 그 위에 값을 더하거나 고친다.
 * 관리자가 넣은 값은 같은 키의 기본 자료보다 먼저 쓰이고, 서버를 다시 켜도 그대로 남는다.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "4. 교통 API — 요금·패스", description = "운영사 요금표, 역 쌍 확정 요금, 신칸센 영업킬로, 교통 패스")
public class FareController {

    private final FareDataService fareData;

    @GetMapping("/api/v1/transit/passes")
    @Operation(summary = "교통 패스 목록",
            description = "등록된 교통 패스의 가격·유효 기간·조건·공식 안내 주소입니다. city 를 주면 그 글자가 지역 이름에 들어간 패스만 돌려줍니다(예: 도쿄, 오사카, 전국). "
                    + "일정에 맞는 패스 추천은 일정 생성 응답의 passes 에 들어 있습니다.")
    public List<TransportPassDto> passes(@RequestParam(required = false) String city) {
        return fareData.listPasses(city, false);
    }

    @PostMapping("/api/v1/admin/transit/fare-data/sync")
    @Operation(summary = "기본 요금 자료 다시 맞추기",
            description = "프로젝트의 resources/transit/fare-tables.json, passes.json 을 읽어 DB 의 기본 자료(source=bundled)를 통째로 바꿉니다. "
                    + "서버가 켜질 때 자동으로 실행되므로, 파일을 고친 뒤 서버를 끄지 않고 반영하고 싶을 때만 씁니다. 관리자 API 로 넣은 값(manual)은 그대로 둡니다.")
    public ResponseEntity<String> sync() {
        try {
            return ResponseEntity.ok(fareData.syncBundled());
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/api/v1/admin/transit/fare-data/status")
    @Operation(summary = "요금 자료 상태", description = "지금 요금 계산에 쓰이는 요금표·역 쌍 요금·영업킬로 노선·패스의 수")
    public Map<String, Object> status() {
        return fareData.status();
    }

    // ---------------------------------------------------------------- 운영사 요금표

    @GetMapping("/api/v1/admin/transit/fare-tables")
    @Operation(summary = "운영사 요금표 목록")
    public List<FareTableDto> tables() {
        return fareData.listTables();
    }

    @PostMapping("/api/v1/admin/transit/fare-tables")
    @Operation(summary = "운영사 요금표 등록·수정",
            description = "운영사가 공개한 \"몇 km 까지 얼마\" 표를 넣습니다. 같은 key 의 표(기본 자료 포함)는 이 값으로 바뀝니다. "
                    + "operators 에는 철도망의 운영사 이름 원문(예: 小田急電鉄)을 적습니다. bands 는 \"3:140,7:170,…\" 형식입니다. "
                    + "기본 자료의 표를 쓰지 않으려면 같은 key 로 enabled=false 를 보내면 됩니다.")
    public FareTableDto saveTable(@RequestBody FareTableDto dto) {
        return fareData.saveTable(dto);
    }

    @DeleteMapping("/api/v1/admin/transit/fare-tables/{key}")
    @Operation(summary = "운영사 요금표 삭제", description = "기본 자료에서 온 표는 지워도 서버를 다시 켜면 돌아옵니다.")
    public ResponseEntity<String> deleteTable(@PathVariable String key) {
        return fareData.deleteTable(key) ? ResponseEntity.ok("삭제했습니다: " + key) : ResponseEntity.status(404).body("그 key 의 요금표가 없습니다: " + key);
    }

    // ---------------------------------------------------------------- 역 쌍 요금

    @GetMapping("/api/v1/admin/transit/fare-pairs")
    @Operation(summary = "역 쌍 요금 목록", description = "group 을 주면 그 요금 단위(JR 또는 운영사 이름 원문)의 것만 돌려줍니다.")
    public List<FarePairDto> pairs(@RequestParam(required = false) String group) {
        return fareData.listPairs(group);
    }

    @PostMapping("/api/v1/admin/transit/fare-pairs")
    @Operation(summary = "역 쌍 요금 등록·수정 (여러 건)",
            description = "\"이 역에서 저 역까지 얼마\"를 넣습니다. 요금표로는 나오지 않는 특정 구간 운임이나 신칸센 자유석 특급요금(kind=SURCHARGE)에 씁니다. "
                    + "같은 group·역 쌍·kind 의 이전 값은 바뀝니다. 역 이름은 철도망의 원문(예: 新大阪)으로 적고, 하나라도 형식이 틀리면 아무것도 넣지 않습니다.")
    public ResponseEntity<String> savePairs(@RequestBody List<FarePairDto> dtos) {
        return ResponseEntity.ok(fareData.savePairs(dtos) + "건을 넣었습니다.");
    }

    @DeleteMapping("/api/v1/admin/transit/fare-pairs/{id}")
    @Operation(summary = "역 쌍 요금 삭제")
    public ResponseEntity<String> deletePair(@PathVariable Long id) {
        return fareData.deletePair(id) ? ResponseEntity.ok("삭제했습니다.") : ResponseEntity.status(404).body("그 id 의 역 쌍 요금이 없습니다.");
    }

    // ---------------------------------------------------------------- 영업킬로

    @GetMapping("/api/v1/admin/transit/line-km")
    @Operation(summary = "노선별 영업킬로 목록")
    public List<LineKmDto> lineKm() {
        return fareData.listLineKm();
    }

    @PostMapping("/api/v1/admin/transit/line-km")
    @Operation(summary = "노선 영업킬로 등록·수정",
            description = "운임을 재는 거리가 실제 선로 거리와 다른 노선(신칸센 등)의 역별 누적 거리를 넣습니다. stations 는 \"東京:0,品川:6.8,…\" 형식이며 같은 노선의 이전 값은 바뀝니다.")
    public LineKmDto saveLineKm(@RequestBody LineKmDto dto) {
        return fareData.saveLineKm(dto);
    }

    @DeleteMapping("/api/v1/admin/transit/line-km/{id}")
    @Operation(summary = "노선 영업킬로 삭제")
    public ResponseEntity<String> deleteLineKm(@PathVariable Long id) {
        return fareData.deleteLineKm(id) ? ResponseEntity.ok("삭제했습니다.") : ResponseEntity.status(404).body("그 id 의 영업킬로가 없습니다.");
    }

    // ---------------------------------------------------------------- 패스

    @GetMapping("/api/v1/admin/transit/passes")
    @Operation(summary = "교통 패스 목록 (꺼 둔 것 포함)", description = "추천 계산에 쓰는 범위(coverage)까지 모두 보여 줍니다.")
    public List<TransportPassDto> allPasses() {
        return fareData.listPasses(null, true);
    }

    @PostMapping("/api/v1/admin/transit/passes")
    @Operation(summary = "교통 패스 등록·수정",
            description = "같은 key 의 패스(기본 자료 포함)는 이 값으로 바뀝니다. days(달력 기준 일수)와 hours(처음 쓴 때부터의 시간) 중 하나만 적습니다. "
                    + "coverage 는 탈 수 있는 범위로, 운영사 이름 원문·교통수단 종류·노선·지역을 적은 줄을 하나 이상 넣습니다(여러 줄 중 하나에만 맞으면 탈 수 있음). "
                    + "신칸센은 categories 에 SHINKANSEN 을 적어야 포함됩니다. 가격이 바뀐 패스는 같은 key 로 다시 보내면 됩니다.")
    public TransportPassDto savePass(@RequestBody TransportPassDto dto) {
        return fareData.savePass(dto);
    }

    @DeleteMapping("/api/v1/admin/transit/passes/{key}")
    @Operation(summary = "교통 패스 삭제", description = "기본 자료에서 온 패스는 지워도 서버를 다시 켜면 돌아옵니다. 계속 쓰지 않으려면 같은 key 로 enabled=false 를 등록하세요.")
    public ResponseEntity<String> deletePass(@PathVariable String key) {
        return fareData.deletePass(key) ? ResponseEntity.ok("삭제했습니다: " + key) : ResponseEntity.status(404).body("그 key 의 패스가 없습니다: " + key);
    }
}
