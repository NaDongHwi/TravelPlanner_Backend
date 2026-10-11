package com.travel.planner.service;

import com.travel.planner.entity.TransitFarePair;
import com.travel.planner.entity.TransitFareTable;
import com.travel.planner.entity.TransitLineKm;
import com.travel.planner.entity.TransportPass;
import com.travel.planner.repository.TransitFarePairRepository;
import com.travel.planner.repository.TransitFareTableRepository;
import com.travel.planner.repository.TransitLineKmRepository;
import com.travel.planner.repository.TransportPassRepository;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 테스트용: 요금 자료·패스를 메모리에 쌓아 두는 가짜 저장소와, 그것으로 만든 서비스 */
public final class FareMemory {

    public final List<TransitFareTable> tables = new ArrayList<>();
    public final List<TransitFarePair> pairs = new ArrayList<>();
    public final List<TransitLineKm> lineKms = new ArrayList<>();
    public final List<TransportPass> passes = new ArrayList<>();
    private long nextId = 1;

    public final FareDataService service;

    public FareMemory() {
        TransitFareTableRepository tableRepo = repo(TransitFareTableRepository.class, tables, TransitFareTable::getId, TransitFareTable::setId,
                (method, arg) -> {
                    if (method.equals("findBySource")) return filter(tables, r -> arg.equals(r.getSource()));
                    if (method.equals("findByTableKey")) return filter(tables, r -> arg.equals(r.getTableKey()));
                    return null;
                });
        TransitFarePairRepository pairRepo = repo(TransitFarePairRepository.class, pairs, TransitFarePair::getId, TransitFarePair::setId,
                (method, arg) -> {
                    if (method.equals("findBySource")) return filter(pairs, r -> arg.equals(r.getSource()));
                    if (method.equals("findByGroupName")) return filter(pairs, r -> arg.equals(r.getGroupName()));
                    return null;
                });
        TransitLineKmRepository lineRepo = repo(TransitLineKmRepository.class, lineKms, TransitLineKm::getId, TransitLineKm::setId,
                (method, arg) -> {
                    if (method.equals("findBySource")) return filter(lineKms, r -> arg.equals(r.getSource()));
                    if (method.equals("findByLineName")) return filter(lineKms, r -> arg.equals(r.getLineName()));
                    return null;
                });
        TransportPassRepository passRepo = repo(TransportPassRepository.class, passes, TransportPass::getId, TransportPass::setId,
                (method, arg) -> {
                    if (method.equals("findBySource")) return filter(passes, r -> arg.equals(r.getSource()));
                    if (method.equals("findByPassKey")) return filter(passes, r -> arg.equals(r.getPassKey()));
                    if (method.equals("findByCity")) return filter(passes, r -> arg.equals(r.getCity()));
                    return null;
                });
        FareDataStore store = new FareDataStore(tableRepo, pairRepo, lineRepo, passRepo);
        service = new FareDataService(tableRepo, pairRepo, lineRepo, passRepo, store);
    }

    /** 요금 자료가 하나도 없는 서비스 (요금은 전부 추정으로 계산된다) */
    public static FareDataService emptyService() {
        return new FareMemory().service;
    }

    /** 프로젝트의 기본 자료를 넣은 서비스 */
    public static FareDataService bundledService() {
        FareMemory memory = new FareMemory();
        memory.service.syncBundled();
        return memory.service;
    }

    private interface Finder {
        Object find(String method, Object arg);
    }

    private interface IdSetter<T> {
        void set(T row, Long id);
    }

    @SuppressWarnings("unchecked")
    private <T, R> R repo(Class<R> type, List<T> rows, Function<T, Long> getId, IdSetter<T> setId, Finder finder) {
        return (R) Proxy.newProxyInstance(FareMemory.class.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "findAll":
                    return new ArrayList<>(rows);
                case "save": {
                    T row = (T) args[0];
                    if (getId.apply(row) == null) {
                        setId.set(row, nextId++);
                        rows.add(row);
                    }
                    return row;
                }
                case "saveAll": {
                    List<T> saved = new ArrayList<>();
                    for (Object o : (Iterable<?>) args[0]) {
                        T row = (T) o;
                        if (getId.apply(row) == null) {
                            setId.set(row, nextId++);
                            rows.add(row);
                        }
                        saved.add(row);
                    }
                    return saved;
                }
                case "deleteAllInBatch": {
                    if (args == null || args.length == 0) {
                        rows.clear();
                    } else {
                        List<Object> remove = new ArrayList<>();
                        for (Object o : (Iterable<?>) args[0]) remove.add(o);
                        rows.removeIf(r -> remove.stream().anyMatch(x -> x == r));
                    }
                    return null;
                }
                case "existsById":
                    return rows.stream().anyMatch(r -> args[0].equals(getId.apply(r)));
                case "deleteById":
                    rows.removeIf(r -> args[0].equals(getId.apply(r)));
                    return null;
                case "count":
                    return (long) rows.size();
                default:
                    return finder.find(name, args == null || args.length == 0 ? null : args[0]);
            }
        });
    }

    private static <T> List<T> filter(List<T> rows, java.util.function.Predicate<T> test) {
        return rows.stream().filter(test).collect(Collectors.toList());
    }
}
