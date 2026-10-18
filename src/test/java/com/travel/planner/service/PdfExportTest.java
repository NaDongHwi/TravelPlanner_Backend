package com.travel.planner.service;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.travel.planner.entity.Accommodation;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.entity.Traffic;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PDF 에 개요·경비·숙소·일차별 시간표·이동 정보가 들어가는지 */
class PdfExportTest {

    @Test
    void documentHasOverviewCostsLodgingAndDailyTimetable() throws Exception {
        Plan plan = new Plan();
        plan.setTitle("시즈오카 여행");
        plan.setStartDate(LocalDate.of(2026, 10, 24));
        plan.setEndDate(LocalDate.of(2026, 10, 25));
        plan.setCities("시즈오카");
        plan.setCompanion("친구");
        plan.setAdults(2);
        plan.setInCity("시즈오카");
        plan.setInTime("10:30");
        plan.setOutCity("시즈오카");
        plan.setOutTime("18:20");

        Place park = new Place();
        park.setId(1L);
        park.setName("駿府城公園");
        park.setNameKo("슨푸성 공원");
        park.setCategory("관광지");
        park.setSummary("성터를 정비한 공원");
        park.setAddress("静岡県静岡市葵区駿府城公園1-1");
        park.setOpeningHours("월요일: 오전 9:00~오후 4:30 | 화요일: 오전 9:00~오후 4:30 | 수요일: 오전 9:00~오후 4:30 | 목요일: 오전 9:00~오후 4:30 | "
                + "금요일: 오전 9:00~오후 4:30 | 토요일: 오전 9:00~오후 4:30 | 일요일: 오전 9:00~오후 4:30");
        Place hotel = new Place();
        hotel.setId(2L);
        hotel.setName("호텔 시즈오카");
        hotel.setCategory("숙소");

        Itinerary start = item(10L, 1, 1, "10:30", null, hotel, "숙소 출발");
        Itinerary visit = item(11L, 1, 2, "11:00", "12:30", park, "성터를 정비한 공원");
        Itinerary end = item(12L, 2, 1, "18:00", null, hotel, "숙소 도착 및 휴식");
        plan.addItinerary(start);
        plan.addItinerary(visit);
        plan.addItinerary(end);

        Map<Long, Traffic> moves = new HashMap<>();
        Traffic bus = new Traffic();
        bus.setTransportType("버스");
        bus.setDurationMinutes(15);
        bus.setEstimatedCost(200);
        bus.setPathDetails("시즈테쓰 버스 → 아카토리이");
        moves.put(11L, bus);

        Accommodation stay = new Accommodation();
        stay.setName("호텔 시즈오카");
        stay.setCheckIn(LocalDate.of(2026, 10, 24));
        stay.setCheckOut(LocalDate.of(2026, 10, 25));
        stay.setPriceYen(12000);
        stay.setPriceSource("rakuten");

        byte[] bytes = PdfExportService.render(plan, List.of(stay), moves, LocalDate.of(2026, 10, 18));
        String text = text(bytes);

        assertTrue(text.contains("1박 2일"), text);
        assertTrue(text.contains("친구 · 어른 2명"));
        assertTrue(text.contains("6,200엔"), "1인 경비 = 교통비 200 + 숙박비 12,000 ÷ 2");
        assertTrue(text.contains("라쿠텐 최저가"));
        assertTrue(text.contains("10월 24일 (토)"));
        assertTrue(text.contains("슨푸성 공원"));
        assertTrue(text.contains("駿府城公園"), "원래 이름(일본 한자 포함)도 찍힌다");
        assertTrue(text.contains("버스 15분 · 200엔"));
        assertTrue(text.contains("오전 9:00~오후 4:30"));
        assertTrue(text.contains("1 / 3"), "쪽 번호");
    }

    @Test
    void weeklyHoursCollapseWhenEveryDayIsTheSame() {
        String same = "월요일: 24시간 영업 | 화요일: 24시간 영업 | 수요일: 24시간 영업 | 목요일: 24시간 영업 | 금요일: 24시간 영업 | 토요일: 24시간 영업 | 일요일: 24시간 영업";
        assertEquals("매일 24시간 영업", PdfExportService.weeklyHours(same));
        String differ = same.replace("수요일: 24시간 영업", "수요일: 휴무일");
        assertFalse(PdfExportService.weeklyHours(differ).startsWith("매일"));
        assertEquals("1시간 5분", PdfExportService.minutes(65));
        assertEquals("12,000엔", PdfExportService.yen(12000));
    }

    private static Itinerary item(Long id, int day, int seq, String time, String end, Place place, String comment) {
        Itinerary it = new Itinerary();
        it.setId(id);
        it.setDayNumber(day);
        it.setSequence(seq);
        it.setTime(time);
        it.setEndTime(end);
        it.setPlace(place);
        it.setAiComment(comment);
        return it;
    }

    private static String text(byte[] pdf) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            for (int i = 1; i <= doc.getNumberOfPages(); i++) sb.append(PdfTextExtractor.getTextFromPage(doc.getPage(i))).append('\n');
        }
        return sb.toString();
    }
}
