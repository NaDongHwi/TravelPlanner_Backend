package com.travel.planner.dto;

import java.util.List;

public class AiRouteResponse {
    private List<String> finalRouteNames; // 최종 확정된 장소 이름들
    private String reason;                // AI가 작성한 추천 사유

    // Getter, Setter (Lombok이 있으면 @Data 하나로 끝납니다)
    public List<String> getFinalRouteNames() { return finalRouteNames; }
    public void setFinalRouteNames(List<String> finalRouteNames) { this.finalRouteNames = finalRouteNames; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}