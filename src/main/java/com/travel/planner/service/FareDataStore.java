package com.travel.planner.service;

import com.travel.planner.entity.TransitFarePair;
import com.travel.planner.entity.TransitFareTable;
import com.travel.planner.entity.TransitLineKm;
import com.travel.planner.entity.TransportPass;
import com.travel.planner.repository.TransitFarePairRepository;
import com.travel.planner.repository.TransitFareTableRepository;
import com.travel.planner.repository.TransitLineKmRepository;
import com.travel.planner.repository.TransportPassRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 요금 자료·패스를 DB 에 넣고 뺀다.
 * 기본 자료(bundled)를 통째로 바꾸는 일은 중간에 실패해도 이전 자료가 남도록 한 트랜잭션으로 처리한다.
 */
@Service
@RequiredArgsConstructor
public class FareDataStore {

    private final TransitFareTableRepository tableRepository;
    private final TransitFarePairRepository pairRepository;
    private final TransitLineKmRepository lineKmRepository;
    private final TransportPassRepository passRepository;

    /** 기본 자료에서 온 행을 모두 지우고 새로 넣는다. 관리자가 직접 넣은 행(manual)은 건드리지 않는다. */
    @Transactional
    public void replaceBundled(List<TransitFareTable> tables, List<TransitFarePair> pairs, List<TransitLineKm> lineKms,
                               List<TransportPass> passes) {
        tableRepository.deleteAllInBatch(tableRepository.findBySource(TransitFareTable.BUNDLED));
        pairRepository.deleteAllInBatch(pairRepository.findBySource(TransitFareTable.BUNDLED));
        lineKmRepository.deleteAllInBatch(lineKmRepository.findBySource(TransitFareTable.BUNDLED));
        passRepository.deleteAllInBatch(passRepository.findBySource(TransitFareTable.BUNDLED));
        tableRepository.saveAll(tables);
        pairRepository.saveAll(pairs);
        lineKmRepository.saveAll(lineKms);
        passRepository.saveAll(passes);
    }

    /** 같은 키의 이전 행(기본 자료 포함)을 지우고 관리자가 넣은 행으로 바꾼다 */
    @Transactional
    public TransitFareTable replaceTable(TransitFareTable row) {
        tableRepository.deleteAllInBatch(tableRepository.findByTableKey(row.getTableKey()));
        return tableRepository.save(row);
    }

    @Transactional
    public int deleteTable(String key) {
        List<TransitFareTable> rows = tableRepository.findByTableKey(key);
        tableRepository.deleteAllInBatch(rows);
        return rows.size();
    }

    @Transactional
    public void replacePairs(List<TransitFarePair> remove, List<TransitFarePair> add) {
        pairRepository.deleteAllInBatch(remove);
        pairRepository.saveAll(add);
    }

    @Transactional
    public TransitLineKm replaceLineKm(TransitLineKm row) {
        lineKmRepository.deleteAllInBatch(lineKmRepository.findByLineName(row.getLineName()));
        return lineKmRepository.save(row);
    }

    @Transactional
    public TransportPass replacePass(TransportPass row) {
        passRepository.deleteAllInBatch(passRepository.findByPassKey(row.getPassKey()));
        return passRepository.save(row);
    }

    @Transactional
    public int deletePass(String key) {
        List<TransportPass> rows = passRepository.findByPassKey(key);
        passRepository.deleteAllInBatch(rows);
        return rows.size();
    }
}
