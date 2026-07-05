package com.travel.planner.service;

import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.Region;
import com.travel.planner.util.PrefectureMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlanValidationService {

    private final GoogleMapsService googleMapsService;

    public static class ValidationResult {
        public boolean isWarning;
        public String warningMessage;

        public ValidationResult(boolean isWarning, String warningMessage) {
            this.isWarning = isWarning;
            this.warningMessage = warningMessage;
        }
    }

    /**
     * 유저가 선택한 다중 도시 일정이 물리적으로 무리가 없는지 검증합니다.
     */
    public ValidationResult validateMultiCityPlan(List<String> selectedCities, String inCity, String outCity, int nights) {

        // 1. [동적 매핑] 입력받은 모든 도시를 실시간으로 구글 API를 통해 9대 지방 Enum으로 변환합니다.
        Set<Region> regions = selectedCities.stream()
                .map(this::getRegionFromCityName)
                .collect(Collectors.toSet());

        int cityCount = selectedCities.size();
        int regionCount = regions.size();

        Region inRegion = getRegionFromCityName(inCity);
        Region outRegion = getRegionFromCityName(outCity);
        boolean isRoundTrip = (inRegion == outRegion);

        // 유저가 0박을 입력했을 때, 단일 권역 안에서 1~2개 도시만 돌아다니는 거라면 경고 없이 통과시킵니다.
        if (nights == 0) {
            if (regionCount == 1 && cityCount <= 2) {
                return new ValidationResult(false, "OK"); // 도쿄 0박, 혹은 오사카-교토 0박은 가능
            } else {
                // 0박인데 권역을 넘나들거나 도시를 3개 이상 고르면 불가능
                return new ValidationResult(true, "당일치기(0박) 일정으로는 권역 이동이나 너무 많은 도시 방문이 물리적으로 불가능에 가깝습니다. 계속하시겠습니까?");
            }
        }

        // 2. 피로도 점수 계산기
        int requiredNights = 0;
        requiredNights += cityCount; // 도시당 1박 기본
        requiredNights += (regionCount - 1); // 권역 횡단 페널티

        if (isRoundTrip && regionCount >= 2) {
            requiredNights += 2; // 타 권역 왕복 페널티
        }

        // 극단적 엣지 케이스 (북해도 + 오키나와 동시 선택) 방어
        if (regions.contains(Region.HOKKAIDO) && regions.contains(Region.OKINAWA)) {
            requiredNights += 4;
        }

        // 3. 최종 판단
        if (nights < requiredNights) {
            String msg = String.format("선택하신 도시 수(%d개)와 이동 동선에 비해 %d박은 매우 촉박합니다. 최소 %d박 이상을 권장합니다. 그래도 이대로 일정을 생성하시겠습니까?",
                    cityCount, nights, requiredNights);
            return new ValidationResult(true, msg);
        }

        return new ValidationResult(false, "OK");
    }

    /**
     * [구글 API + 9대 지방 매퍼 연동]
     * 하드 코딩 없이, 입력된 텍스트 지명을 구글 정식 주소로 바꾼 뒤 정확한 Region을 찾아냅니다.
     */
    private Region getRegionFromCityName(String cityName) {
        try {
            // "도쿄" 혹은 검색한 "치바" -> 구글 Geocoding을 통해 정식 주소 획득
            String formalAddress = googleMapsService.getFormalizedJapanCity(cityName);

            // 정식 주소 문자열에서 47개 도도부현을 매핑하여 9대 지방 Enum 반환 (실패 시 여기서 예외 발생)
            return PrefectureMapper.getRegionFromAddress(formalAddress);

        } catch (Exception e) {
            // 구글에서 못 찾거나 일본 외의 지역인 경우 수집 및 검증을 즉시 전면 차단
            throw new IllegalArgumentException("유효한 일본 내 권역을 확인할 수 없는 도시가 포함되어 있습니다: " + cityName);
        }
    }

    public ValidationResult validateAccommodations(List<PlanRequest.AccommodationInput> accommodations, LocalDate tripStart, LocalDate tripEnd) {
        if (accommodations == null || accommodations.isEmpty()) return new ValidationResult(false, "OK");

        // 1. 체크인 날짜순으로 정렬
        accommodations.sort(Comparator.comparing(PlanRequest.AccommodationInput::getCheckIn));

        for (int i = 0; i < accommodations.size(); i++) {
            PlanRequest.AccommodationInput current = accommodations.get(i);

            // [검증 1] 숙소 날짜가 전체 여행 기간을 벗어나는지 확인
            if (current.getCheckIn().isBefore(tripStart) || current.getCheckOut().isAfter(tripEnd)) {
                return new ValidationResult(true, "[" + current.getName() + "] 숙소의 일정이 전체 여행 기간을 벗어납니다.");
            }

            // [검증 2] 다음 숙소와 날짜가 겹치는지 확인 (가장 중요!)
            if (i < accommodations.size() - 1) {
                PlanRequest.AccommodationInput next = accommodations.get(i + 1);
                // 현재 숙소의 체크아웃 날짜가 다음 숙소의 체크인 날짜보다 늦다면? (오버랩 발생)
                if (current.getCheckOut().isAfter(next.getCheckIn())) {
                    return new ValidationResult(true, "숙소 일정이 겹칩니다! [" + current.getName() + "]와 [" + next.getName() + "]의 날짜를 다시 확인해주세요.");
                }
            }
        }
        return new ValidationResult(false, "OK");
    }
}