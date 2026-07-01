package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.util.DistanceUtil;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PlanService {

    // K-Means 클러스터링 알고리즘 (장소들을 일자별로 묶어주는 역할)
    public Map<Integer, List<Place>> clusterPlaces(List<Place> places, int kDays) {
        Map<Integer, List<Place>> clusters = new HashMap<>();
        if (places == null || places.isEmpty()) return clusters;

        // 1. 초기 중심점(Centroid) 랜덤 설정: 입력받은 장소 중 k개를 뽑아 초기 기준으로 삼습니다.
        List<double[]> centroids = new ArrayList<>();
        Random random = new Random();
        for (int i = 0; i < kDays; i++) {
            Place randomPlace = places.get(random.nextInt(places.size()));
            centroids.add(new double[]{randomPlace.getLatitude(), randomPlace.getLongitude()});
        }

        boolean isChanged = true;
        int maxIterations = 100; // 무한 루프 방지용
        int iteration = 0;

        while (isChanged && iteration < maxIterations) {
            // 그룹 초기화
            clusters.clear();
            for (int i = 0; i < kDays; i++) {
                clusters.put(i, new ArrayList<>());
            }

            // 2. 할당(Assignment) 단계: 각 장소를 가장 가까운 중심점에 배정합니다.
            for (Place place : places) {
                int nearestClusterIndex = 0;
                double minDistance = Double.MAX_VALUE;

                for (int i = 0; i < centroids.size(); i++) {
                    double[] centroid = centroids.get(i);
                    double distance = DistanceUtil.calculateDistance(
                            place.getLatitude(), place.getLongitude(),
                            centroid[0], centroid[1]
                    );

                    if (distance < minDistance) {
                        minDistance = distance;
                        nearestClusterIndex = i;
                    }
                }
                clusters.get(nearestClusterIndex).add(place);
            }

            // 3. 업데이트(Update) 단계: 배정된 장소들의 평균 좌표를 구해 새로운 중심점으로 이동시킵니다.
            isChanged = false;
            for (int i = 0; i < kDays; i++) {
                List<Place> clusterPlaces = clusters.get(i);
                if (clusterPlaces.isEmpty()) continue;

                double sumLat = 0, sumLon = 0;
                for (Place p : clusterPlaces) {
                    sumLat += p.getLatitude();
                    sumLon += p.getLongitude();
                }

                double newLat = sumLat / clusterPlaces.size();
                double newLon = sumLon / clusterPlaces.size();

                // 중심점이 이동했다면 계속 반복 (변화가 없으면 루프 종료)
                if (centroids.get(i)[0] != newLat || centroids.get(i)[1] != newLon) {
                    centroids.get(i)[0] = newLat;
                    centroids.get(i)[1] = newLon;
                    isChanged = true;
                }
            }
            iteration++;
        }

        return clusters; // 최종적으로 묶인 그룹(일자별 장소 리스트)을 반환합니다.
    }

    // 2. TSP(Traveling Salesman Problem) 최단 거리 동선 계산 로직
    // K-Means로 묶인 하루치 장소들(dayPlaces)의 방문 순서를 최적화합니다.
    public List<Place> calculateShortestPath(List<Place> dayPlaces) {
        if (dayPlaces == null || dayPlaces.size() <= 1) return dayPlaces;

        List<Place> bestRoute = new ArrayList<>();
        double[] minDistance = {Double.MAX_VALUE};
        boolean[] visited = new boolean[dayPlaces.size()];
        List<Place> currentRoute = new ArrayList<>();

        // 모든 장소를 한 번씩 시작점으로 삼아 모든 경우의 수를 탐색(DFS)하여 완벽한 최단 거리를 찾습니다.
        for (int i = 0; i < dayPlaces.size(); i++) {
            visited[i] = true;
            currentRoute.add(dayPlaces.get(i));

            // 재귀 함수(DFS) 호출 시작
            dfs(dayPlaces, visited, currentRoute, bestRoute, minDistance, 0.0);

            currentRoute.remove(currentRoute.size() - 1);
            visited[i] = false;
        }

        return bestRoute; // 최종적으로 순서가 가장 예쁘게 정렬된 장소 리스트를 반환합니다.
    }

    // 💡 TSP 탐색을 위한 깊이 우선 탐색(DFS) 및 백트래킹(Backtracking) 로직
    private void dfs(List<Place> places, boolean[] visited, List<Place> currentRoute, List<Place> bestRoute, double[] minDistance, double currentDist) {
        // 모든 장소를 다 방문했을 때 (하나의 루트가 완성되었을 때)
        if (currentRoute.size() == places.size()) {
            if (currentDist < minDistance[0]) {
                minDistance[0] = currentDist; // 최소 거리 갱신
                bestRoute.clear();
                bestRoute.addAll(currentRoute); // 가장 짧은 루트 저장
            }
            return;
        }

        Place lastPlace = currentRoute.get(currentRoute.size() - 1);

        for (int i = 0; i < places.size(); i++) {
            if (!visited[i]) {
                visited[i] = true;
                Place nextPlace = places.get(i);

                // 하버사인 거리 계산기 꺼내 쓰기
                double dist = DistanceUtil.calculateDistance(
                        lastPlace.getLatitude(), lastPlace.getLongitude(),
                        nextPlace.getLatitude(), nextPlace.getLongitude()
                );

                currentRoute.add(nextPlace);

                // 현재까지의 거리가 이미 알려진 최소 거리보다 짧을 때만 계속 탐색 (연산 속도 최적화)
                if (currentDist + dist < minDistance[0]) {
                    dfs(places, visited, currentRoute, bestRoute, minDistance, currentDist + dist);
                }

                currentRoute.remove(currentRoute.size() - 1);
                visited[i] = false;
            }
        }
    }
}