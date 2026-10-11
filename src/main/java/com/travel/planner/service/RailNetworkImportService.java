package com.travel.planner.service;

import com.travel.planner.transit.RailGraph;
import com.travel.planner.transit.RailNetworkParser;
import com.travel.planner.transit.RailTopologyBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 국토교통성 "국토수치정보 철도" 파일을 읽어 전국 철도망(역·역간 연결)을 DB 에 넣는다.
 *
 * 파일은 직접 내려받아 data 폴더에 둔다 (https://nlftp.mlit.go.jp/ksj/ → 4. 交通 → 鉄道, 全国 zip).
 * 가져오기는 기존 철도망을 통째로 바꾼다. 연도가 바뀐 새 파일을 넣고 다시 실행하면 갱신된다.
 */
@Service
@RequiredArgsConstructor
public class RailNetworkImportService {

    private final RailNetworkStore store;
    private final RailRouteService routeService;

    @Value("${transit.rail.data-dir:./data}")
    private String dataDir = "./data";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String lastMessage = "아직 가져온 적이 없습니다.";

    public boolean isRunning() { return running.get(); }
    public String lastMessage() { return lastMessage; }

    /**
     * 가져올 파일을 정한다. path 를 주면 그 파일(또는 폴더), 없으면 data 폴더에서 이름이 N02 로 시작하는 zip 중 가장 최근 것.
     */
    public Path resolveFile(String path) throws IOException {
        if (path != null && !path.isBlank()) {
            Path given = Path.of(path.trim());
            if (!Files.exists(given)) throw new IOException("파일이 없습니다: " + given.toAbsolutePath());
            return given;
        }
        Path dir = Path.of(dataDir);
        if (!Files.isDirectory(dir)) {
            throw new IOException("'" + dir.toAbsolutePath() + "' 폴더가 없습니다. 국토수치정보 철도 zip 파일을 이 폴더에 넣어 주세요.");
        }
        try (Stream<Path> list = Files.list(dir)) {
            List<Path> candidates = list
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.startsWith("n02") && name.endsWith(".zip");
                    })
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) {
                throw new IOException("'" + dir.toAbsolutePath() + "' 폴더에 N02-xx_GML.zip 파일이 없습니다. 국토수치정보 철도 데이터를 내려받아 넣어 주세요.");
            }
            return candidates.get(0);
        }
    }

    @Async("adminTaskExecutor")
    public void importAsync(Path file) {
        if (!running.compareAndSet(false, true)) return;
        try {
            lastMessage = importNow(file);
        } catch (Exception e) {
            lastMessage = "가져오기 실패: " + e.getMessage() + " (기존 철도망은 그대로입니다)";
        } finally {
            running.set(false);
            System.out.println("[철도망 가져오기] " + lastMessage);
        }
    }

    /** 파일 읽기 → 역 순서·거리 계산 → DB 교체. 결과 요약 문장을 돌려준다. */
    public String importNow(Path file) throws IOException {
        long started = System.currentTimeMillis();
        System.out.println("[철도망 가져오기] " + file.toAbsolutePath() + " 읽는 중...");
        RailNetworkParser.RawNetwork raw = RailNetworkParser.read(file);
        RailTopologyBuilder.Result topology = RailTopologyBuilder.build(raw);
        if (topology.stations.isEmpty() || topology.links.isEmpty()) {
            throw new IOException("파일에서 역이나 선로를 읽지 못했습니다 (역 " + topology.stations.size() + "개, 연결 " + topology.links.size() + "개).");
        }
        RailGraph check = RailGraph.of(topology);   // 저장 전에 그래프가 만들어지는지 확인
        System.out.println("[철도망 가져오기] 역 " + topology.stations.size() + "개, 역간 연결 " + topology.links.size()
                + "개 계산 완료. DB 에 저장합니다... (수십 초 걸릴 수 있습니다)");

        store.replaceAll(topology, raw.source);
        routeService.invalidate();

        return String.format("%s 에서 노선 %d개, 역 %d개, 역간 연결 %d개, 환승 %d개를 가져왔습니다. (선로에 붙이지 못한 역 %d개, %d초)",
                raw.source, topology.lineCount, topology.stations.size(), topology.links.size(), check.transferLinkCount(),
                topology.unlinkedStations, (System.currentTimeMillis() - started) / 1000);
    }
}
