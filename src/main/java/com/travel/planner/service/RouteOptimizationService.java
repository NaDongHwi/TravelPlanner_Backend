package com.travel.planner.service;

import com.travel.planner.dto.RouteInfoDto;
import com.travel.planner.entity.TransportPass;
import com.travel.planner.repository.TransportPassRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class RouteOptimizationService {

    private final NavitimeRouteService navitimeRouteService;
    private final TransportPassRepository transportPassRepository;

    public RouteOptimizationService(NavitimeRouteService navitimeRouteService, TransportPassRepository transportPassRepository) {
        this.navitimeRouteService = navitimeRouteService;
        this.transportPassRepository = transportPassRepository;
    }

    public RouteInfoDto getOptimizedRoute(double startLat, double startLng, double goalLat, double goalLng, String cityName) {
        RouteInfoDto routeInfo = navitimeRouteService.getRouteInfo(startLat, startLng, goalLat, goalLng);

        if (routeInfo == null) {
            return null;
        }

        // IC 카드 요금이 0엔(제공 안 됨)일 경우 일반 표 요금을 기본 최적 요금으로 설정
        int defaultOptimalFare;
        if (routeInfo.getIcCardFare() > 0) {
            defaultOptimalFare = Math.min(routeInfo.getTicketFare(), routeInfo.getIcCardFare());
        } else {
            defaultOptimalFare = routeInfo.getTicketFare();
        }

        routeInfo.setOptimalFare(defaultOptimalFare);
        routeInfo.setOptimizationMessage("일반 표 또는 IC 카드를 이용하는 것이 가장 저렴합니다.");

        // TransportPassRepository 사용
        List<TransportPass> availablePasses = transportPassRepository.findByCity(cityName);
        Set<String> routeOperators = routeInfo.getOperators();

        if (routeOperators == null || routeOperators.isEmpty()) {
            return routeInfo;
        }

        for (TransportPass pass : availablePasses) {
            boolean canUsePass = true;
            String coverage = pass.getCoverageDescription() != null ? pass.getCoverageDescription() : "";

            // 탑승한 모든 운영사(Operator)가 패스권 설명(coverageDescription)에 명시되어 있는지 텍스트 매칭
            for (String operator : routeOperators) {
                if (!coverage.contains(operator)) {
                    canUsePass = false;
                    break;
                }
            }

            // 조건 충족 및 패스권 가격이 일반 요금보다 저렴할 경우 최적 요금 갱신 (priceEnyen 필드 사용)
            if (canUsePass && pass.getPriceEnyen() < routeInfo.getOptimalFare()) {
                routeInfo.setOptimalFare(pass.getPriceEnyen());
                routeInfo.setRecommendedPassName(pass.getName());

                int savedAmount = defaultOptimalFare - pass.getPriceEnyen();
                routeInfo.setOptimizationMessage(
                        String.format("'%s'를 구매하시면 일반 요금 대비 %d엔 절약됩니다.", pass.getName(), savedAmount)
                );
            }
        }

        return routeInfo;
    }
}