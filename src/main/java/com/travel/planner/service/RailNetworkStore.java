package com.travel.planner.service;

import com.travel.planner.entity.TransitLink;
import com.travel.planner.entity.TransitStation;
import com.travel.planner.repository.TransitLinkRepository;
import com.travel.planner.repository.TransitStationRepository;
import com.travel.planner.transit.RailTopologyBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 철도망을 DB 에 통째로 바꿔 넣는다. 중간에 실패하면 이전 데이터가 그대로 남도록 한 트랜잭션으로 처리한다. */
@Service
@RequiredArgsConstructor
public class RailNetworkStore {

    private static final int CHUNK = 500;

    private final TransitStationRepository stationRepository;
    private final TransitLinkRepository linkRepository;

    @Transactional
    public void replaceAll(RailTopologyBuilder.Result topology, String source) {
        linkRepository.deleteAllInBatch();
        stationRepository.deleteAllInBatch();

        // 역 저장 (저장하면서 받은 DB id 를 연결 저장에 쓴다)
        List<TransitStation> saved = new ArrayList<>(topology.stations.size());
        List<TransitStation> chunk = new ArrayList<>();
        for (RailTopologyBuilder.StationRecord r : topology.stations) {
            TransitStation s = new TransitStation();
            s.setCode(r.code);
            s.setGroupCode(r.groupCode);
            s.setName(r.name);
            s.setOperator(r.operator == null ? "" : r.operator);
            s.setLine(r.line == null ? "" : r.line);
            s.setRailClass(r.railClass);
            s.setOperatorType(r.operatorType);
            s.setCategory(r.category.name());
            s.setLatitude(r.latitude);
            s.setLongitude(r.longitude);
            s.setSource(source);
            chunk.add(s);
            if (chunk.size() == CHUNK) {
                saved.addAll(stationRepository.saveAll(chunk));
                chunk = new ArrayList<>();
            }
        }
        if (!chunk.isEmpty()) saved.addAll(stationRepository.saveAll(chunk));

        List<TransitLink> links = new ArrayList<>();
        for (RailTopologyBuilder.LinkRecord l : topology.links) {
            TransitLink link = new TransitLink();
            link.setFromStationId(saved.get(l.from).getId());
            link.setToStationId(saved.get(l.to).getId());
            link.setDistanceKm(l.km);
            links.add(link);
            if (links.size() == CHUNK) {
                linkRepository.saveAll(links);
                links = new ArrayList<>();
            }
        }
        if (!links.isEmpty()) linkRepository.saveAll(links);
    }
}
