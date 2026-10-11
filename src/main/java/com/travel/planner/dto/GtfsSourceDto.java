package com.travel.planner.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** 가져올 GTFS 피드 하나 (기본 목록의 한 줄, 또는 관리자가 직접 지정한 주소). */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class GtfsSourceDto {
    private String key;          // 짧은 이름 (영문 소문자·숫자·-). DB 의 feedKey
    private String name;         // 표시 이름
    private List<String> cities = new ArrayList<>();   // 관련 도시 (한국어)
    private String url;          // zip 주소. ODPT 주소는 끝이 "acl:consumerKey=" 이고 키는 설정에서 붙인다
    private String path;         // 주소 대신 서버의 파일을 쓸 때
    private String license;      // 이용 조건·출처 표시
    private String page;         // 안내 페이지
    private Boolean includeRail; // 철도·지하철·모노레일 노선도 가져올지 (기본은 건너뜀: 전국 철도망과 겹친다)
    private Boolean imported;    // (목록 조회 응답) 이미 가져왔는가
}
