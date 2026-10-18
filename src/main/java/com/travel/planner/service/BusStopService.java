package com.travel.planner.service;

import com.travel.planner.transit.BusStopIndex;
import com.travel.planner.transit.BusStopParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 전국 버스 정류장 자료(국토수치정보 P11)를 메모리에 올린다.
 *
 * data 폴더(transit.rail.data-dir)에 이름이 P11 로 시작하는 zip 을 넣어 두면 서버가 켜진 뒤 뒤에서 읽는다(전국 약 25초).
 * 읽는 동안이나 파일이 없으면 이 자료 없이 경로를 계산한다(일정 생성은 막지 않는다).
 * DB 에는 넣지 않는다: 파일을 바꾸고 서버를 다시 켜면 새 자료를 쓴다.
 */
@Service
public class BusStopService {

    @Value("${transit.rail.data-dir:./data}")
    private String dataDir = "./data";

    @Value("${transit.bus-stops.enabled:true}")
    private boolean enabled = true;

    private volatile BusStopIndex index;
    private volatile String status = "아직 읽지 않았습니다.";

    /** 읽어 둔 색인. 아직 없으면 null */
    public BusStopIndex index() {
        return index;
    }

    /** 테스트·관리용: 읽어 둔 색인을 바꾼다 */
    void use(BusStopIndex loaded) {
        this.index = loaded;
        this.status = loaded == null ? "비어 있습니다." : loaded.sourceName() + ": 정류장 " + loaded.stopCount() + "개";
    }

    public String status() {
        return status;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadInBackground() {
        if (!enabled) {
            status = "꺼져 있습니다 (transit.bus-stops.enabled=false).";
            return;
        }
        Thread t = new Thread(this::load, "bus-stops");
        t.setDaemon(true);
        t.start();
    }

    synchronized void load() {
        try {
            Optional<Path> file = findFile(Path.of(dataDir));
            if (file.isEmpty()) {
                status = "'" + Path.of(dataDir).toAbsolutePath() + "' 폴더에 버스 정류장 파일(P11-22_SHP.zip)이 없습니다.";
                System.out.println("[교통] 전국 버스 정류장 자료가 없어 철도망·GTFS 버스만 사용합니다. (" + status + ")");
                return;
            }
            long started = System.currentTimeMillis();
            System.out.println("[교통] 전국 버스 정류장 자료를 읽는 중입니다: " + file.get().getFileName() + " (전국 파일은 30초쯤 걸립니다)");
            BusStopIndex loaded = BusStopParser.read(file.get());
            index = loaded;
            status = loaded.sourceName() + ": 정류장 " + loaded.stopCount() + "개, 노선 " + loaded.routeCount() + "개";
            System.out.println("[교통] 전국 버스 정류장 자료를 읽었습니다: " + status + " (" + (System.currentTimeMillis() - started) + "ms)");
        } catch (Exception e) {
            status = "읽지 못했습니다: " + e.getMessage();
            System.out.println("[교통] 전국 버스 정류장 자료를 읽지 못해 철도망·GTFS 버스만 사용합니다: " + e.getMessage());
        }
    }

    /** 이름이 p11 로 시작하는 zip 중 가장 큰 것 (전국 파일이 있으면 그것) */
    static Optional<Path> findFile(Path dir) throws java.io.IOException {
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.startsWith("p11") && name.endsWith(".zip") && Files.isRegularFile(p);
            }).max(Comparator.comparingLong(p -> {
                try {
                    return Files.size(p);
                } catch (Exception e) {
                    return 0L;
                }
            }));
        }
    }
}
