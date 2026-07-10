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

    // Nearest Neighbor(그리디) + 2-Opt(지그재그 교차 꼬임 보정) 알고리즘 적용
    public List<Place> calculateShortestPath(List<Place> dayPlaces) {
        if (dayPlaces == null || dayPlaces.size() <= 1) return dayPlaces;

        List<Place> route = new ArrayList<>();
        // 아직 방문하지 않은 장소 리스트를 복사해서 만듭니다.
        List<Place> unvisited = new ArrayList<>(dayPlaces);

        // 1단계: 초기 경로 생성 (Nearest Neighbor: 가장 가까운 곳 찾아가기)
        Place current = unvisited.remove(0);
        route.add(current);

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

        // 2단계: 2-Opt 최적화 루프 (X자로 교차하며 지그재그로 꼬인 선분 발견 시 순서 반전으로 펴주기)
        boolean improved = true;
        while (improved) {
            improved = false;

            // 처음과 끝이 고정되지 않은 유연한 선형 동선이므로 모든 유효 구간을 탐색합니다.
            for (int i = 1; i < route.size() - 2; i++) {
                for (int k = i + 1; k < route.size() - 1; k++) {

                    // 꼬여있을 때 두 선분의 거리 합 계산
                    double distBefore = DistanceUtil.calculateDistance(route.get(i - 1).getLatitude(), route.get(i - 1).getLongitude(), route.get(i).getLatitude(), route.get(i).getLongitude())
                            + DistanceUtil.calculateDistance(route.get(k).getLatitude(), route.get(k).getLongitude(), route.get(k + 1).getLatitude(), route.get(k + 1).getLongitude());

                    // 선분을 교차 결합(순서 뒤집기)했을 때의 거리 합 계산
                    double distAfter = DistanceUtil.calculateDistance(route.get(i - 1).getLatitude(), route.get(i - 1).getLongitude(), route.get(k).getLatitude(), route.get(k).getLongitude())
                            + DistanceUtil.calculateDistance(route.get(i).getLatitude(), route.get(i).getLongitude(), route.get(k + 1).getLatitude(), route.get(k + 1).getLongitude());

                    // 순서를 뒤집는 게 전체 총 거리를 단 0.1km라도 단축시킨다면 교체 수행
                    if (distAfter < distBefore) {
                        reverseSubList(route, i, k);
                        improved = true; // 경로가 개선되었으므로 다음 전체 스캔 유도
                    }
                }
            }
        }

        return route;
    }

    // 2-Opt 경로 개선 시 특정 구간(i부터 k까지)의 순서를 역순으로 뒤집어주는 함수
    private void reverseSubList(List<Place> route, int i, int k) {
        while (i < k) {
            Place temp = route.get(i);
            route.set(i, route.get(k));
            route.set(k, temp);
            i++;
            k--;
        }
    }
}