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

    // DFS 대신 Nearest Neighbor(그리디) 방식 적용
    public List<Place> calculateShortestPath(List<Place> dayPlaces) {
        if (dayPlaces == null || dayPlaces.size() <= 1) return dayPlaces;

        List<Place> route = new ArrayList<>();
        // 아직 방문하지 않은 장소 리스트를 복사해서 만듭니다.
        List<Place> unvisited = new ArrayList<>(dayPlaces);

        // 1. 첫 번째 장소를 시작점으로 잡습니다.
        Place current = unvisited.remove(0);
        route.add(current);

        // 2. 남은 장소가 없을 때까지, 현재 위치에서 '가장 가까운 장소'를 찾아 다음 목적지로 이어붙입니다.
        while (!unvisited.isEmpty()) {
            Place nearest = null;
            double minDistance = Double.MAX_VALUE;

            for (Place candidate : unvisited) {
                // 하버사인 공식으로 거리 계산
                double dist = DistanceUtil.calculateDistance(
                        current.getLatitude(), current.getLongitude(),
                        candidate.getLatitude(), candidate.getLongitude()
                );

                if (dist < minDistance) {
                    minDistance = dist;
                    nearest = candidate;
                }
            }

            // 가장 가까운 장소를 경로에 추가하고, 미방문 리스트에서 제거
            route.add(nearest);
            unvisited.remove(nearest);
            // 현재 위치를 방금 찾은 장소로 이동
            current = nearest;
        }

        return route; // 0.01초 만에 정렬된 임시 동선 뼈대 반환
    }
}