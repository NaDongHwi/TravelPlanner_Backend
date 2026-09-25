package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.dto.RouteInfoDto;
import com.travel.planner.dto.RouteSegmentDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class NavitimeRouteService {

    @Value("${navitime.api-key}")
    private String navitimeApiKey;

    // 프로젝트 환경에 맞춰 빈(Bean) 주입 대신 직접 생성 방식으로 변경하여 오류 방지
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RouteInfoDto getRouteInfo(double startLat, double startLng, double goalLat, double goalLng) {
        String startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        String url = String.format(
                "https://navitime-route-totalnavi.p.rapidapi.com/route_transit?" +
                        "start=%f,%f&goal=%f,%f&start_time=%s&limit=1&datum=wgs84",
                startLat, startLng, goalLat, goalLng, startTime
        );

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-rapidapi-host", "navitime-route-totalnavi.p.rapidapi.com");
            headers.set("x-rapidapi-key", navitimeApiKey);

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            RouteInfoDto dto = new RouteInfoDto();

            JsonNode itemsNode = rootNode.path("items");
            if (itemsNode.isArray() && !itemsNode.isEmpty()) {
                JsonNode firstRoute = itemsNode.get(0);
                JsonNode summaryNode = firstRoute.path("summary");
                JsonNode moveNode = summaryNode.path("move");

                dto.setTotalTime(moveNode.path("time").asInt());

                JsonNode referenceFareNode = moveNode.path("reference_fare");
                if (!referenceFareNode.isMissingNode()) {
                    dto.setTicketFare(referenceFareNode.path("lowest_total_ticket").asInt(0));
                    dto.setIcCardFare(referenceFareNode.path("lowest_total_ic").asInt(0));
                }

                List<String> pathDetails = new ArrayList<>();
                Set<String> operators = new HashSet<>();
                List<RouteSegmentDto> segments = new ArrayList<>();

                JsonNode sectionsNode = firstRoute.path("sections");
                if (sectionsNode.isArray()) {
                    for (JsonNode section : sectionsNode) {
                        String type = section.path("type").asText();

                        if ("move".equals(type)) {
                            String moveType = section.path("move").asText();

                            if (!"walk".equals(moveType)) {
                                JsonNode transportNode = section.path("transport");

                                if (!transportNode.isMissingNode()) {
                                    RouteSegmentDto segmentDto = new RouteSegmentDto();

                                    String lineName = transportNode.path("name").asText();
                                    int segmentTime = section.path("time").asInt(0);

                                    JsonNode fareNode = transportNode.path("fare");
                                    int fare = fareNode.isMissingNode() ? 0 : fareNode.path("fare").asInt(0);

                                    String operatorName = lineName;
                                    JsonNode companyNode = transportNode.path("company");
                                    if (!companyNode.isMissingNode() && companyNode.has("name")) {
                                        operatorName = companyNode.path("name").asText();
                                    }

                                    segmentDto.setLineName(lineName);
                                    segmentDto.setOperator(operatorName);
                                    segmentDto.setTimeMinutes(segmentTime);
                                    segmentDto.setSegmentFare(fare);
                                    segments.add(segmentDto);

                                    pathDetails.add(lineName + " (" + segmentTime + "분, " + fare + "엔)");
                                    operators.add(operatorName);
                                }
                            }
                        }
                    }
                }
                dto.setSegments(segments);
                dto.setPathDetails(pathDetails);
                dto.setOperators(operators);
            }
            return dto;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}