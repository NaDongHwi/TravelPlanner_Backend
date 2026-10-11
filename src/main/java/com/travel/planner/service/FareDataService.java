package com.travel.planner.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.dto.FarePairDto;
import com.travel.planner.dto.FareTableDto;
import com.travel.planner.dto.LineKmDto;
import com.travel.planner.dto.TransportPassDto;
import com.travel.planner.entity.TransitFarePair;
import com.travel.planner.entity.TransitFareTable;
import com.travel.planner.entity.TransitLineKm;
import com.travel.planner.entity.TransportPass;
import com.travel.planner.repository.TransitFarePairRepository;
import com.travel.planner.repository.TransitFareTableRepository;
import com.travel.planner.repository.TransitLineKmRepository;
import com.travel.planner.repository.TransportPassRepository;
import com.travel.planner.transit.FareBook;
import com.travel.planner.transit.PassAdvisor;
import com.travel.planner.transit.PassCodec;
import com.travel.planner.transit.RailCategory;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 확인해서 등록한 요금 자료(운영사 요금표·역 쌍 요금·신칸센 영업킬로)와 교통 패스를 관리한다.
 *
 * 자료가 들어오는 길은 둘이다.
 *   - 기본 자료: 프로젝트의 resources/transit/fare-tables.json, passes.json. 서버가 켜질 때마다 DB 와 맞춘다 (source = bundled).
 *   - 관리자 API: 직접 넣거나 고친 값 (source = manual). 같은 키의 기본 자료보다 먼저 쓰고, 서버를 다시 켜도 덮어쓰지 않는다.
 * 요금을 운영사 사이트에서 자동으로 긁어 오지는 않는다. 사이트마다 형식이 달라 쉽게 깨지기 때문에, 확인한 값을 적어 두는 방식이다.
 */
@Service
@RequiredArgsConstructor
public class FareDataService {

    static final String TABLES_RESOURCE = "transit/fare-tables.json";
    static final String PASSES_RESOURCE = "transit/passes.json";

    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final TransitFareTableRepository tableRepository;
    private final TransitFarePairRepository pairRepository;
    private final TransitLineKmRepository lineKmRepository;
    private final TransportPassRepository passRepository;
    private final FareDataStore store;

    private volatile FareBook book;
    private volatile List<PassAdvisor.Pass> passes;
    private volatile Map<String, TransportPass> passRows = new HashMap<>();

    /** 기본 자료 파일의 형식 */
    @Getter
    @Setter
    public static class FareSeed {
        private String version;
        private List<FareTableDto> tables = new ArrayList<>();
        private List<FarePairDto> pairs = new ArrayList<>();
        private List<LineKmDto> lineKm = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class PassSeed {
        private String version;
        private List<TransportPassDto> passes = new ArrayList<>();
    }

    // =====================================================================
    // 기본 자료 맞추기
    // =====================================================================

    /** 서버가 다 켜진 뒤 기본 자료를 DB 와 맞춘다. 실패해도 서버는 계속 뜬다 (요금은 추정으로 계산된다). */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            System.out.println("[요금] " + syncBundled());
        } catch (Exception e) {
            System.out.println("[요금] 기본 요금 자료를 넣지 못했습니다. 요금은 추정값으로 계산됩니다: " + e.getMessage());
        }
    }

    /** 프로젝트에 들어 있는 기본 자료를 읽어 DB 의 bundled 행을 통째로 바꾼다. 관리자가 넣은 같은 키의 행이 있으면 그 항목은 건너뛴다. */
    public String syncBundled() {
        FareSeed fareSeed = readResource(TABLES_RESOURCE, FareSeed.class);
        PassSeed passSeed = readResource(PASSES_RESOURCE, PassSeed.class);
        List<String> problems = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        Set<String> manualTables = new HashSet<>();
        Set<String> manualPairs = new HashSet<>();
        Set<String> manualLines = new HashSet<>();
        Set<String> manualPasses = new HashSet<>();
        for (TransitFareTable row : tableRepository.findBySource(TransitFareTable.MANUAL)) manualTables.add(row.getTableKey());
        for (TransitFarePair row : pairRepository.findBySource(TransitFareTable.MANUAL)) manualPairs.add(pairIdentity(row));
        for (TransitLineKm row : lineKmRepository.findBySource(TransitFareTable.MANUAL)) manualLines.add(row.getLineName());
        for (TransportPass row : passRepository.findBySource(TransitFareTable.MANUAL)) manualPasses.add(row.getPassKey());

        List<TransitFareTable> tables = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (FareTableDto dto : fareSeed.getTables()) {
            try {
                TransitFareTable row = toRow(dto, TransitFareTable.BUNDLED, now);
                if (!seen.add("T" + row.getTableKey())) throw new IllegalArgumentException("키가 겹칩니다.");
                if (!manualTables.contains(row.getTableKey())) tables.add(row);
            } catch (IllegalArgumentException e) {
                problems.add("요금표 " + dto.getKey() + ": " + e.getMessage());
            }
        }
        List<TransitFarePair> pairs = new ArrayList<>();
        for (FarePairDto dto : fareSeed.getPairs()) {
            try {
                TransitFarePair row = toRow(dto, TransitFareTable.BUNDLED, now);
                if (!seen.add("P" + pairIdentity(row))) throw new IllegalArgumentException("같은 역 쌍이 두 번 있습니다.");
                if (!manualPairs.contains(pairIdentity(row))) pairs.add(row);
            } catch (IllegalArgumentException e) {
                problems.add("역 쌍 요금 " + dto.getA() + "–" + dto.getB() + ": " + e.getMessage());
            }
        }
        List<TransitLineKm> lineKms = new ArrayList<>();
        for (LineKmDto dto : fareSeed.getLineKm()) {
            try {
                TransitLineKm row = toRow(dto, TransitFareTable.BUNDLED, now);
                if (!seen.add("L" + row.getLineName())) throw new IllegalArgumentException("같은 노선이 두 번 있습니다.");
                if (!manualLines.contains(row.getLineName())) lineKms.add(row);
            } catch (IllegalArgumentException e) {
                problems.add("영업킬로 " + dto.getLine() + ": " + e.getMessage());
            }
        }
        List<TransportPass> passRowsToSave = new ArrayList<>();
        for (TransportPassDto dto : passSeed.getPasses()) {
            try {
                TransportPass row = toRow(dto, TransitFareTable.BUNDLED, now);
                if (!seen.add("S" + row.getPassKey())) throw new IllegalArgumentException("키가 겹칩니다.");
                if (!manualPasses.contains(row.getPassKey())) passRowsToSave.add(row);
            } catch (IllegalArgumentException e) {
                problems.add("패스 " + dto.getKey() + ": " + e.getMessage());
            }
        }

        store.replaceBundled(tables, pairs, lineKms, passRowsToSave);
        invalidate();
        String message = "기본 요금 자료를 맞췄습니다: 요금표 " + tables.size() + "개, 역 쌍 요금 " + pairs.size() + "건, 영업킬로 노선 "
                + lineKms.size() + "개, 패스 " + passRowsToSave.size() + "종 (자료 판: " + fareSeed.getVersion() + " / " + passSeed.getVersion() + ")";
        if (!problems.isEmpty()) message += " — 읽지 못한 항목 " + problems.size() + "개: " + String.join(" | ", problems);
        return message;
    }

    private static <T> T readResource(String name, Class<T> type) {
        try (InputStream in = FareDataService.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("기본 자료 파일이 없습니다: " + name);
            return MAPPER.readValue(in, type);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("기본 자료 파일을 읽지 못했습니다(" + name + "): " + e.getMessage());
        }
    }

    /** 프로젝트에 들어 있는 기본 자료를 그대로 읽는다 (DB 없이 쓰는 검증용) */
    public static FareSeed bundledFares() {
        return readResource(TABLES_RESOURCE, FareSeed.class);
    }

    public static PassSeed bundledPasses() {
        return readResource(PASSES_RESOURCE, PassSeed.class);
    }

    /** 기본 자료만으로 만든 요금 자료 (DB 없이 쓰는 검증용). 형식이 틀린 항목이 있으면 IllegalArgumentException */
    public static FareBook bundledBook() {
        FareSeed seed = bundledFares();
        LocalDateTime now = LocalDateTime.now();
        List<FareBook.Table> tables = new ArrayList<>();
        for (FareTableDto dto : seed.getTables()) tables.add(toTable(toRow(dto, TransitFareTable.BUNDLED, now)));
        List<FareBook.Pair> pairs = new ArrayList<>();
        for (FarePairDto dto : seed.getPairs()) pairs.add(toPair(toRow(dto, TransitFareTable.BUNDLED, now)));
        Map<String, Map<String, Double>> lineKm = new HashMap<>();
        for (LineKmDto dto : seed.getLineKm()) lineKm.put(dto.getLine(), FareBook.parseKm(dto.getStations()));
        return new FareBook(tables, pairs, lineKm);
    }

    /** 기본 자료만으로 만든 패스 목록 (DB 없이 쓰는 검증용) */
    public static List<PassAdvisor.Pass> bundledPassList() {
        List<PassAdvisor.Pass> list = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (TransportPassDto dto : bundledPasses().getPasses()) list.add(toPass(toRow(dto, TransitFareTable.BUNDLED, now)));
        return list;
    }

    static FareBook.Pair toPair(TransitFarePair row) {
        FareBook.Pair pair = new FareBook.Pair();
        pair.group = row.getGroupName();
        pair.stationA = row.getStationA();
        pair.stationB = row.getStationB();
        pair.adultYen = row.getAdultYen();
        pair.childYen = row.getChildYen();
        pair.surcharge = TransitFarePair.SURCHARGE.equals(row.getFareKind());
        return pair;
    }

    // =====================================================================
    // 메모리에 올려 쓰기
    // =====================================================================

    /** 요금이나 패스가 바뀌었을 때: 다음에 쓸 때 DB 에서 다시 읽는다 */
    public synchronized void invalidate() {
        book = null;
        passes = null;
    }

    /** 경로 요금 계산에 쓰는 요금 자료. 테이블이 없거나 읽지 못하면 빈 자료(전부 추정) */
    public FareBook fareBook() {
        FareBook current = book;
        if (current != null) return current;
        synchronized (this) {
            if (book != null) return book;
            try {
                List<FareBook.Table> tables = new ArrayList<>();
                // 관리자가 넣은 표가 기본 자료보다 먼저 오도록 한다 (같은 조건이면 먼저 등록된 표를 쓴다)
                List<TransitFareTable> rows = new ArrayList<>(tableRepository.findAll());
                rows.sort((a, b) -> Boolean.compare(!TransitFareTable.MANUAL.equals(a.getSource()), !TransitFareTable.MANUAL.equals(b.getSource())));
                int broken = 0;
                for (TransitFareTable row : rows) {
                    if (Boolean.FALSE.equals(row.getEnabled())) continue;
                    try {
                        tables.add(toTable(row));
                    } catch (IllegalArgumentException e) {
                        broken++;
                    }
                }
                List<FareBook.Pair> pairs = new ArrayList<>();
                for (TransitFarePair row : pairRepository.findAll()) {
                    if (row.getGroupName() == null || row.getStationA() == null || row.getStationB() == null || row.getAdultYen() == null) continue;
                    pairs.add(toPair(row));
                }
                Map<String, Map<String, Double>> lineKm = new HashMap<>();
                for (TransitLineKm row : lineKmRepository.findAll()) {
                    try {
                        lineKm.put(row.getLineName(), FareBook.parseKm(row.getStations()));
                    } catch (IllegalArgumentException e) {
                        broken++;
                    }
                }
                book = new FareBook(tables, pairs, lineKm);
                if (broken > 0) System.out.println("[요금] 형식이 맞지 않아 건너뛴 요금 자료가 " + broken + "건 있습니다.");
            } catch (Exception e) {
                System.out.println("[요금] 요금 자료를 읽지 못해 추정값만 사용합니다: " + e.getMessage());
                book = FareBook.EMPTY;
            }
            return book;
        }
    }

    /** 추천 계산에 쓰는 패스 목록 (사용 중인 것만) */
    public List<PassAdvisor.Pass> passes() {
        List<PassAdvisor.Pass> current = passes;
        if (current != null) return current;
        synchronized (this) {
            if (passes != null) return passes;
            List<PassAdvisor.Pass> list = new ArrayList<>();
            Map<String, TransportPass> rows = new LinkedHashMap<>();
            try {
                for (TransportPass row : passRepository.findAll()) {
                    if (row.getPassKey() == null || Boolean.FALSE.equals(row.getEnabled())) continue;
                    try {
                        PassAdvisor.Pass pass = toPass(row);
                        if (pass.covers.isEmpty()) continue;
                        list.add(pass);
                        rows.put(row.getPassKey(), row);
                    } catch (IllegalArgumentException e) {
                        System.out.println("[요금] 패스 " + row.getPassKey() + " 의 범위를 읽지 못해 건너뜁니다: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.out.println("[요금] 패스 목록을 읽지 못했습니다: " + e.getMessage());
            }
            passRows = rows;
            passes = list;
            return list;
        }
    }

    /** 패스의 안내용 정보 (이름·조건·주소). 없으면 null */
    public TransportPass passRow(String key) {
        passes();
        return passRows.get(key);
    }

    // =====================================================================
    // 관리: 목록 · 등록 · 삭제
    // =====================================================================

    public List<FareTableDto> listTables() {
        List<FareTableDto> list = new ArrayList<>();
        for (TransitFareTable row : tableRepository.findAll()) list.add(toDto(row));
        list.sort((a, b) -> String.valueOf(a.getKey()).compareTo(String.valueOf(b.getKey())));
        return list;
    }

    /** 같은 키의 표를 이 값으로 바꾼다 (기본 자료의 같은 키도 대신한다) */
    public FareTableDto saveTable(FareTableDto dto) {
        TransitFareTable row = store.replaceTable(toRow(dto, TransitFareTable.MANUAL, LocalDateTime.now()));
        invalidate();
        return toDto(row);
    }

    public boolean deleteTable(String key) {
        boolean removed = store.deleteTable(key) > 0;
        invalidate();
        return removed;
    }

    public List<FarePairDto> listPairs(String group) {
        List<FarePairDto> list = new ArrayList<>();
        for (TransitFarePair row : group == null || group.isBlank() ? pairRepository.findAll() : pairRepository.findByGroupName(group.trim())) {
            list.add(toDto(row));
        }
        return list;
    }

    /** 역 쌍 요금을 넣는다. 같은 묶음·역 쌍·종류의 이전 값은 바꾼다. 하나라도 형식이 틀리면 아무것도 넣지 않는다. */
    public int savePairs(List<FarePairDto> dtos) {
        if (dtos == null || dtos.isEmpty()) throw new IllegalArgumentException("넣을 역 쌍 요금이 없습니다.");
        LocalDateTime now = LocalDateTime.now();
        Map<String, TransitFarePair> add = new LinkedHashMap<>();
        for (FarePairDto dto : dtos) {
            TransitFarePair row = toRow(dto, TransitFareTable.MANUAL, now);
            add.put(pairIdentity(row), row);
        }
        List<TransitFarePair> remove = new ArrayList<>();
        for (TransitFarePair row : pairRepository.findAll()) {
            if (add.containsKey(pairIdentity(row))) remove.add(row);
        }
        store.replacePairs(remove, new ArrayList<>(add.values()));
        invalidate();
        return add.size();
    }

    public boolean deletePair(Long id) {
        if (id == null || !pairRepository.existsById(id)) return false;
        pairRepository.deleteById(id);
        invalidate();
        return true;
    }

    public List<LineKmDto> listLineKm() {
        List<LineKmDto> list = new ArrayList<>();
        for (TransitLineKm row : lineKmRepository.findAll()) list.add(toDto(row));
        return list;
    }

    public LineKmDto saveLineKm(LineKmDto dto) {
        TransitLineKm row = store.replaceLineKm(toRow(dto, TransitFareTable.MANUAL, LocalDateTime.now()));
        invalidate();
        return toDto(row);
    }

    public boolean deleteLineKm(Long id) {
        if (id == null || !lineKmRepository.existsById(id)) return false;
        lineKmRepository.deleteById(id);
        invalidate();
        return true;
    }

    /**
     * @param city            이 글자가 도시 이름에 들어간 패스만 (null 이면 전부)
     * @param includeDisabled 꺼 둔 패스와 추천 계산에 쓸 수 없는 예전 행도 포함할지
     */
    public List<TransportPassDto> listPasses(String city, boolean includeDisabled) {
        List<TransportPassDto> list = new ArrayList<>();
        for (TransportPass row : passRepository.findAll()) {
            if (!includeDisabled && (row.getPassKey() == null || Boolean.FALSE.equals(row.getEnabled()))) continue;
            if (city != null && !city.isBlank() && (row.getCity() == null || !row.getCity().contains(city.trim()))) continue;
            list.add(toDto(row));
        }
        list.sort((a, b) -> {
            int byCity = String.valueOf(a.getCity()).compareTo(String.valueOf(b.getCity()));
            return byCity != 0 ? byCity : Integer.compare(a.getPriceYen() == null ? 0 : a.getPriceYen(), b.getPriceYen() == null ? 0 : b.getPriceYen());
        });
        return list;
    }

    public TransportPassDto savePass(TransportPassDto dto) {
        TransportPass row = store.replacePass(toRow(dto, TransitFareTable.MANUAL, LocalDateTime.now()));
        invalidate();
        return toDto(row);
    }

    public boolean deletePass(String key) {
        boolean removed = store.deletePass(key) > 0;
        invalidate();
        return removed;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        FareBook current = fareBook();
        status.put("fareTables", current.tableCount());
        status.put("farePairs", current.pairCount());
        status.put("lineKmLines", current.lineKmCount());
        status.put("passes", passes().size());
        return status;
    }

    // =====================================================================
    // 형식 변환과 검증 (잘못된 값은 IllegalArgumentException)
    // =====================================================================

    static String pairIdentity(TransitFarePair row) {
        String a = row.getStationA();
        String b = row.getStationB();
        return row.getGroupName() + "|" + (a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a) + "|" + row.getFareKind();
    }

    static TransitFareTable toRow(FareTableDto dto, String source, LocalDateTime now) {
        if (dto == null) throw new IllegalArgumentException("요금표 내용이 비어 있습니다.");
        TransitFareTable row = new TransitFareTable();
        row.setTableKey(required(dto.getKey(), "key(식별자)"));
        row.setName(required(dto.getName(), "name(이름)"));
        List<String> operators = clean(dto.getOperators());
        if (operators.isEmpty()) throw new IllegalArgumentException("operators(운영사)를 하나 이상 적어 주세요.");
        row.setOperators(String.join(",", operators));
        row.setCategories(emptyToNull(String.join(",", clean(dto.getCategories()))));
        row.setLineFilter(emptyToNull(String.join(",", clean(dto.getLines()))));
        row.setExceptLines(emptyToNull(String.join(",", clean(dto.getExceptLines()))));
        row.setAreaBoxes(emptyToNull(dto.getBoxes()));
        row.setBands(required(dto.getBands(), "bands(요금 구간)"));
        row.setStationAdds(emptyToNull(dto.getStationAdds()));
        row.setChildRoundDown(dto.isChildRoundDown());
        row.setEffectiveFrom(emptyToNull(dto.getEffectiveFrom()));
        row.setReferenceUrl(emptyToNull(dto.getReference()));
        row.setVerifiedAt(date(dto.getVerifiedAt()));
        row.setNote(emptyToNull(dto.getNote()));
        row.setSource(source);
        row.setEnabled(dto.getEnabled() == null || dto.getEnabled());
        row.setUpdatedAt(now);
        toTable(row);   // 형식 검증
        return row;
    }

    static FareBook.Table toTable(TransitFareTable row) {
        FareBook.Table table = new FareBook.Table();
        table.key = row.getTableKey();
        table.name = row.getName();
        table.operators.addAll(FareBook.split(row.getOperators(), ","));
        if (table.operators.isEmpty()) throw new IllegalArgumentException("운영사가 비어 있습니다.");
        table.categories = FareBook.parseCategories(row.getCategories());
        table.lines = FareBook.split(row.getLineFilter(), ",");
        table.exceptLines = FareBook.split(row.getExceptLines(), ",");
        table.boxes = FareBook.parseBoxes(row.getAreaBoxes());
        table.bands = FareBook.parseBands(row.getBands());
        table.stationAdds = FareBook.parseAmounts(row.getStationAdds());
        table.childRoundDown = Boolean.TRUE.equals(row.getChildRoundDown());
        return table;
    }

    static FareTableDto toDto(TransitFareTable row) {
        FareTableDto dto = new FareTableDto();
        dto.setKey(row.getTableKey());
        dto.setName(row.getName());
        dto.setOperators(FareBook.split(row.getOperators(), ","));
        dto.setCategories(FareBook.split(row.getCategories(), ","));
        dto.setLines(FareBook.split(row.getLineFilter(), ","));
        dto.setExceptLines(FareBook.split(row.getExceptLines(), ","));
        dto.setBoxes(row.getAreaBoxes());
        dto.setBands(row.getBands());
        dto.setStationAdds(row.getStationAdds());
        dto.setChildRoundDown(Boolean.TRUE.equals(row.getChildRoundDown()));
        dto.setEffectiveFrom(row.getEffectiveFrom());
        dto.setReference(row.getReferenceUrl());
        dto.setVerifiedAt(row.getVerifiedAt() == null ? null : row.getVerifiedAt().toString());
        dto.setNote(row.getNote());
        dto.setSource(row.getSource());
        dto.setEnabled(!Boolean.FALSE.equals(row.getEnabled()));
        return dto;
    }

    static TransitFarePair toRow(FarePairDto dto, String source, LocalDateTime now) {
        if (dto == null) throw new IllegalArgumentException("역 쌍 요금 내용이 비어 있습니다.");
        TransitFarePair row = new TransitFarePair();
        row.setGroupName(required(dto.getGroup(), "group(요금 단위: JR 또는 운영사 이름)"));
        row.setStationA(required(dto.getA(), "a(역 이름)"));
        row.setStationB(required(dto.getB(), "b(역 이름)"));
        if (row.getStationA().equals(row.getStationB())) throw new IllegalArgumentException("두 역이 같습니다: " + row.getStationA());
        String kind = dto.getKind() == null || dto.getKind().isBlank() ? TransitFarePair.FARE : dto.getKind().trim().toUpperCase();
        if (!kind.equals(TransitFarePair.FARE) && !kind.equals(TransitFarePair.SURCHARGE)) {
            throw new IllegalArgumentException("kind 는 FARE 또는 SURCHARGE 여야 합니다: " + dto.getKind());
        }
        if (kind.equals(TransitFarePair.SURCHARGE) && !"JR".equals(row.getGroupName())) {
            throw new IllegalArgumentException("SURCHARGE(신칸센 특급요금)의 group 은 JR 이어야 합니다.");
        }
        row.setFareKind(kind);
        if (dto.getAdultYen() == null || dto.getAdultYen() <= 0) throw new IllegalArgumentException("adultYen(성인 요금)은 1 이상이어야 합니다.");
        if (dto.getChildYen() != null && dto.getChildYen() < 0) throw new IllegalArgumentException("childYen(아동 요금)은 0 이상이어야 합니다.");
        row.setAdultYen(dto.getAdultYen());
        row.setChildYen(dto.getChildYen());
        row.setReferenceUrl(emptyToNull(dto.getReference()));
        row.setVerifiedAt(date(dto.getVerifiedAt()));
        row.setNote(emptyToNull(dto.getNote()));
        row.setSource(source);
        row.setUpdatedAt(now);
        return row;
    }

    static FarePairDto toDto(TransitFarePair row) {
        FarePairDto dto = new FarePairDto();
        dto.setId(row.getId());
        dto.setGroup(row.getGroupName());
        dto.setA(row.getStationA());
        dto.setB(row.getStationB());
        dto.setKind(row.getFareKind());
        dto.setAdultYen(row.getAdultYen());
        dto.setChildYen(row.getChildYen());
        dto.setReference(row.getReferenceUrl());
        dto.setVerifiedAt(row.getVerifiedAt() == null ? null : row.getVerifiedAt().toString());
        dto.setNote(row.getNote());
        dto.setSource(row.getSource());
        return dto;
    }

    static TransitLineKm toRow(LineKmDto dto, String source, LocalDateTime now) {
        if (dto == null) throw new IllegalArgumentException("영업킬로 내용이 비어 있습니다.");
        TransitLineKm row = new TransitLineKm();
        row.setLineName(required(dto.getLine(), "line(노선 이름)"));
        row.setStations(required(dto.getStations(), "stations(역별 거리)"));
        FareBook.parseKm(row.getStations());   // 형식 검증
        row.setReferenceUrl(emptyToNull(dto.getReference()));
        row.setVerifiedAt(date(dto.getVerifiedAt()));
        row.setNote(emptyToNull(dto.getNote()));
        row.setSource(source);
        row.setUpdatedAt(now);
        return row;
    }

    static LineKmDto toDto(TransitLineKm row) {
        LineKmDto dto = new LineKmDto();
        dto.setId(row.getId());
        dto.setLine(row.getLineName());
        dto.setStations(row.getStations());
        dto.setReference(row.getReferenceUrl());
        dto.setVerifiedAt(row.getVerifiedAt() == null ? null : row.getVerifiedAt().toString());
        dto.setNote(row.getNote());
        dto.setSource(row.getSource());
        return dto;
    }

    static TransportPass toRow(TransportPassDto dto, String source, LocalDateTime now) {
        if (dto == null) throw new IllegalArgumentException("패스 내용이 비어 있습니다.");
        TransportPass row = new TransportPass();
        row.setPassKey(required(dto.getKey(), "key(식별자)"));
        row.setName(required(dto.getName(), "name(이름)"));
        row.setCity(required(dto.getCity(), "city(도시·지역)"));
        if (dto.getPriceYen() == null || dto.getPriceYen() <= 0) throw new IllegalArgumentException("priceYen(성인 가격)은 1 이상이어야 합니다.");
        row.setPriceEnyen(dto.getPriceYen());
        row.setChildPriceYen(dto.getChildPriceYen());
        int days = dto.getDays() == null ? 0 : dto.getDays();
        int hours = dto.getHours() == null ? 0 : dto.getHours();
        if (days < 0 || hours < 0 || (days == 0) == (hours == 0)) {
            throw new IllegalArgumentException("days(달력 기준 일수)와 hours(처음 쓴 때부터의 시간) 중 하나만 적어 주세요.");
        }
        row.setValidityHours(hours > 0 ? hours : null);
        row.setValidityDays(hours > 0 ? (hours + 23) / 24 : days);
        row.setConsecutive(dto.getConsecutive() == null || dto.getConsecutive());
        String rule = dto.getDayRule() == null || dto.getDayRule().isBlank() ? PassAdvisor.DayRule.ANY.name() : dto.getDayRule().trim().toUpperCase();
        try {
            PassAdvisor.DayRule.valueOf(rule);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("dayRule 은 ANY, WEEKDAY, HOLIDAY 중 하나여야 합니다: " + dto.getDayRule());
        }
        row.setDayRule(rule);
        List<String> extra = new ArrayList<>();
        if (dto.getExtraDaysOfMonth() != null) {
            for (Integer day : dto.getExtraDaysOfMonth()) {
                if (day == null || day < 1 || day > 31) throw new IllegalArgumentException("extraDaysOfMonth 는 1~31 이어야 합니다: " + day);
                extra.add(String.valueOf(day));
            }
        }
        row.setExtraDaysOfMonth(extra.isEmpty() ? null : String.join(",", extra));
        row.setYearEndAsHoliday(Boolean.TRUE.equals(dto.getYearEndAsHoliday()));
        List<PassAdvisor.Cover> covers = toCovers(dto.getCoverage());
        if (covers.isEmpty()) throw new IllegalArgumentException("coverage(탈 수 있는 범위)를 한 줄 이상 적어 주세요.");
        row.setCoverage(PassCodec.toJson(covers));
        row.setCoverageDescription(emptyToNull(dto.getCoverageDescription()));
        row.setConditions(emptyToNull(dto.getConditions()));
        row.setApproximate(Boolean.TRUE.equals(dto.getApproximate()));
        row.setUrl(emptyToNull(dto.getUrl()));
        row.setVerifiedAt(date(dto.getVerifiedAt()));
        row.setNote(emptyToNull(dto.getNote()));
        row.setSource(source);
        row.setEnabled(dto.getEnabled() == null || dto.getEnabled());
        row.setLastUpdated(now);
        return row;
    }

    private static List<PassAdvisor.Cover> toCovers(List<TransportPassDto.CoverDto> dtos) {
        List<PassAdvisor.Cover> covers = new ArrayList<>();
        if (dtos == null) return covers;
        for (TransportPassDto.CoverDto dto : dtos) {
            PassAdvisor.Cover cover = new PassAdvisor.Cover();
            cover.operators = clean(dto.getOperators());
            cover.categories = FareBook.parseCategories(String.join(",", clean(dto.getCategories())));
            cover.lines = clean(dto.getLines());
            cover.exceptLines = clean(dto.getExceptLines());
            cover.exceptStations = clean(dto.getExceptStations());
            cover.boxes = FareBook.parseBoxes(dto.getBoxes());
            if (cover.operators.isEmpty() && cover.categories.isEmpty() && cover.lines.isEmpty() && cover.boxes.isEmpty()) {
                throw new IllegalArgumentException("범위 한 줄에는 운영사·종류·노선·지역 중 하나는 있어야 합니다.");
            }
            covers.add(cover);
        }
        return covers;
    }

    static PassAdvisor.Pass toPass(TransportPass row) {
        PassAdvisor.Pass pass = new PassAdvisor.Pass();
        pass.key = row.getPassKey();
        pass.name = row.getName();
        pass.city = row.getCity();
        pass.priceYen = row.getPriceEnyen() == null ? 0 : row.getPriceEnyen();
        pass.childPriceYen = row.getChildPriceYen();
        pass.hours = row.getValidityHours() == null ? 0 : row.getValidityHours();
        pass.days = pass.hours > 0 ? 0 : (row.getValidityDays() == null ? 0 : row.getValidityDays());
        pass.consecutive = !Boolean.FALSE.equals(row.getConsecutive());
        try {
            pass.dayRule = row.getDayRule() == null ? PassAdvisor.DayRule.ANY : PassAdvisor.DayRule.valueOf(row.getDayRule());
        } catch (IllegalArgumentException e) {
            pass.dayRule = PassAdvisor.DayRule.ANY;
        }
        for (String day : FareBook.split(row.getExtraDaysOfMonth(), ",")) {
            try {
                pass.extraDaysOfMonth.add(Integer.parseInt(day));
            } catch (NumberFormatException ignored) {
                // 잘못 적힌 날짜는 무시한다
            }
        }
        pass.yearEndAsHoliday = Boolean.TRUE.equals(row.getYearEndAsHoliday());
        pass.covers = PassCodec.parseCovers(row.getCoverage());
        return pass;
    }

    static TransportPassDto toDto(TransportPass row) {
        TransportPassDto dto = new TransportPassDto();
        dto.setKey(row.getPassKey());
        dto.setName(row.getName());
        dto.setCity(row.getCity());
        dto.setPriceYen(row.getPriceEnyen());
        dto.setChildPriceYen(row.getChildPriceYen());
        if (row.getValidityHours() != null && row.getValidityHours() > 0) dto.setHours(row.getValidityHours());
        else dto.setDays(row.getValidityDays());
        dto.setConsecutive(!Boolean.FALSE.equals(row.getConsecutive()));
        dto.setDayRule(row.getDayRule() == null ? PassAdvisor.DayRule.ANY.name() : row.getDayRule());
        List<Integer> extra = new ArrayList<>();
        for (String day : FareBook.split(row.getExtraDaysOfMonth(), ",")) {
            try {
                extra.add(Integer.parseInt(day));
            } catch (NumberFormatException ignored) {
                // 잘못 적힌 날짜는 무시한다
            }
        }
        dto.setExtraDaysOfMonth(extra);
        dto.setYearEndAsHoliday(Boolean.TRUE.equals(row.getYearEndAsHoliday()));
        List<TransportPassDto.CoverDto> coverage = new ArrayList<>();
        try {
            for (PassAdvisor.Cover cover : PassCodec.parseCovers(row.getCoverage())) {
                TransportPassDto.CoverDto c = new TransportPassDto.CoverDto();
                c.setOperators(cover.operators);
                List<String> categories = new ArrayList<>();
                for (RailCategory category : cover.categories) categories.add(category.name());
                c.setCategories(categories);
                c.setLines(cover.lines);
                c.setExceptLines(cover.exceptLines);
                c.setExceptStations(cover.exceptStations);
                c.setBoxes(cover.boxes.isEmpty() ? null : FareBook.formatBoxes(cover.boxes));
                coverage.add(c);
            }
        } catch (IllegalArgumentException ignored) {
            // 범위를 읽을 수 없는 예전 행은 범위 없이 내보낸다
        }
        dto.setCoverage(coverage);
        dto.setCoverageDescription(row.getCoverageDescription());
        dto.setConditions(row.getConditions());
        dto.setApproximate(Boolean.TRUE.equals(row.getApproximate()));
        dto.setUrl(row.getUrl());
        dto.setVerifiedAt(row.getVerifiedAt() == null ? null : row.getVerifiedAt().toString());
        dto.setNote(row.getNote());
        dto.setSource(row.getSource());
        dto.setEnabled(!Boolean.FALSE.equals(row.getEnabled()));
        return dto;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " 을(를) 적어 주세요.");
        return value.trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> clean(List<String> values) {
        List<String> list = new ArrayList<>();
        if (values == null) return list;
        for (String value : values) {
            if (value != null && !value.isBlank()) list.add(value.trim());
        }
        return list;
    }

    private static LocalDate date(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDate.parse(text.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("날짜는 2026-10-11 같은 형식이어야 합니다: " + text);
        }
    }
}
