package com.travel.planner.service;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfDocumentInfo;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.AreaBreak;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.font.FontProvider;
import com.itextpdf.layout.properties.AreaBreakType;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.travel.planner.entity.Accommodation;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.entity.Traffic;
import com.travel.planner.repository.PlanRepository;
import com.travel.planner.repository.TrafficRepository;
import com.travel.planner.util.OpeningHours;
import com.travel.planner.util.PlaceDescriber;
import com.travel.planner.util.PlaceNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 여행 일정 PDF (A4).
 *
 *  1쪽: 여행 개요(기간·도시·동행·테마·입출국) + 예상 경비(교통비·숙박비·1인 경비) + 숙소 + AI 코멘트
 *  일차마다 새 쪽: 그 날의 날짜·도시·방문 수·이동 시간·교통비, 그리고 시간표
 *    - 장소 줄: 이름(한국어), 세부 유형, 원래 이름, 한 줄 소개, 주소, 그 날의 영업시간, 전화, 평점
 *    - 장소 사이: 다음 장소까지 가는 방법·시간·요금 (일정 생성 때 저장한 값)
 *  모든 쪽 아래: 만든 날짜와 쪽 번호
 *
 * 글꼴은 NanumGothic 이고, 그 글꼴에 없는 일본 한자(静·浅 등)는 보조 글꼴(NotoSansJP-Fallback)로 찍는다.
 */
@Service
@RequiredArgsConstructor
public class PdfExportService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 색
    private static final Color NAVY = new DeviceRgb(0x1F, 0x3A, 0x5F);
    private static final Color NAVY_SOFT = new DeviceRgb(0xE8, 0xEE, 0xF5);
    private static final Color TEAL = new DeviceRgb(0x0F, 0x76, 0x6E);
    private static final Color TEAL_SOFT = new DeviceRgb(0xE6, 0xF4, 0xF2);
    private static final Color INK = new DeviceRgb(0x22, 0x27, 0x2E);
    private static final Color GRAY = new DeviceRgb(0x6B, 0x72, 0x80);
    private static final Color LINE = new DeviceRgb(0xDD, 0xE2, 0xE8);
    private static final Color PAPER = new DeviceRgb(0xF6, 0xF7, 0xF9);
    private static final Color AMBER = new DeviceRgb(0xB4, 0x53, 0x09);
    private static final Color WHITE = new DeviceRgb(0xFF, 0xFF, 0xFF);

    private static final String FONT_MAIN = "fonts/NanumGothic.ttf";
    private static final String FONT_JP = "fonts/NotoSansJP-Fallback.otf";

    private final PlaceNameService placeNameService;
    private final PlanRepository planRepository;
    private final TrafficRepository trafficRepository;

    /** 일정표에 적을 장소 이름: 자유 시간·식사·고정 일정은 저장된 표시 이름, 장소는 한국어로 옮긴 이름(없으면 원래 이름) */
    static String placeTitle(Itinerary itinerary) {
        if (itinerary.getCustomTitle() != null) return itinerary.getCustomTitle();
        return itinerary.getPlace() != null ? itinerary.getPlace().shownName() : "-";
    }

    /** "2026년 10월 17일 ~ 2026년 10월 20일" 또는, 날짜를 정하지 않은 일정이면 "3박 4일 (날짜 미정)" */
    static String periodLabel(Plan plan, DateTimeFormatter formatter) {
        if (plan.undated()) {
            int days = plan.totalDays();
            return nightsLabel(days) + " (날짜 미정)";
        }
        return plan.getStartDate().format(formatter) + " ~ " + plan.getEndDate().format(formatter);
    }

    static String nightsLabel(int days) {
        return days <= 1 ? "당일치기" : (days - 1) + "박 " + days + "일";
    }

    /** 일정에 나오는 장소 (자유 시간 같은 자리 표시용 장소는 뺀다) */
    static List<Place> placesOf(Plan plan) {
        return plan.getItineraries().stream().map(Itinerary::getPlace)
                .filter(p -> p != null && !PlanService.isPseudoCategory(p.getCategory())).collect(Collectors.toList());
    }

    public byte[] generatePlanPdf(Plan plan) {
        // 이름이 영어·일본어뿐인 장소에 보여 줄 한국어 이름을 붙인다 (이미 있으면 AI 를 부르지 않는다. 예전에 만든 일정도 한국어로 나온다)
        try {
            placeNameService.fillMissing(placesOf(plan));
        } catch (Exception e) {
            System.out.println("[장소 이름] 한국어 이름을 붙이지 못해 원래 이름으로 문서를 만듭니다: " + e.getMessage());
        }

        List<Accommodation> stays = new ArrayList<>();
        Map<Long, Traffic> moves = new HashMap<>();
        try {
            if (plan.getId() != null) stays = planRepository.findAccommodationsByPlanId(plan.getId());
            if (!plan.getItineraries().isEmpty()) {
                for (Traffic t : trafficRepository.findByItineraryIn(plan.getItineraries())) {
                    if (t.getItinerary() != null && t.getItinerary().getId() != null) moves.put(t.getItinerary().getId(), t);
                }
            }
        } catch (Exception e) {
            System.out.println("[PDF] 숙소·이동 정보를 불러오지 못해 일정만 적습니다: " + e.getMessage());
        }
        return render(plan, stays, moves, LocalDate.now(KST));
    }

    // =====================================================================
    // 문서 그리기
    // =====================================================================

    /** 저장소 없이 문서만 만든다 (moves: 줄(Itinerary id) → 그 줄까지 오는 이동 정보) */
    static byte[] render(Plan plan, List<Accommodation> stays, Map<Long, Traffic> moves, LocalDate today) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfDocument pdf = new PdfDocument(new PdfWriter(out));
            PdfDocumentInfo info = pdf.getDocumentInfo();
            info.setTitle(plan.getTitle() + " 일정표");
            info.setCreator("Travel Planner");

            Document doc = new Document(pdf, PageSize.A4, false);   // 쪽 번호를 마지막에 찍기 위해 바로 내보내지 않는다
            doc.setMargins(40, 40, 50, 40);
            applyFonts(doc);
            doc.setFontColor(INK).setFontSize(9.5f);

            List<Itinerary> all = new ArrayList<>(plan.getItineraries());
            Map<Integer, List<Itinerary>> byDay = new TreeMap<>();
            for (Itinerary it : all) {
                byDay.computeIfAbsent(it.getDayNumber() == null ? 1 : it.getDayNumber(), k -> new ArrayList<>()).add(it);
            }
            for (List<Itinerary> list : byDay.values()) {
                list.sort(Comparator.comparing(Itinerary::getSequence, Comparator.nullsLast(Comparator.naturalOrder())));
            }
            List<Place> places = placesOf(plan);
            int adults = plan.getAdults() == null || plan.getAdults() < 1 ? 1 : plan.getAdults();

            cover(doc, plan, adults);
            costs(doc, plan, stays, moves, adults);
            lodging(doc, plan, stays);
            comment(doc, plan, places);
            notes(doc, plan);

            for (Map.Entry<Integer, List<Itinerary>> day : byDay.entrySet()) {
                doc.add(new AreaBreak(AreaBreakType.NEXT_PAGE));
                dayPage(doc, plan, day.getKey(), day.getValue(), moves);
            }

            footer(doc, pdf, plan, today);
            doc.close();
        } catch (Exception e) {
            throw new RuntimeException("PDF 문서 생성 중 오류가 발생했습니다.", e);
        }
        return out.toByteArray();
    }

    private static void applyFonts(Document doc) {
        FontProvider fonts = new FontProvider();
        List<String> families = new ArrayList<>();
        if (addFont(fonts, FONT_MAIN)) families.add("NanumGothic");
        if (addFont(fonts, FONT_JP)) families.add("Noto Sans CJK JP");
        if (families.isEmpty()) {
            System.err.println("한글 폰트 로드 실패: " + FONT_MAIN);
            return;
        }
        doc.setFontProvider(fonts);
        doc.setFontFamily(families.toArray(new String[0]));
    }

    private static boolean addFont(FontProvider fonts, String path) {
        try {
            byte[] bytes = new org.springframework.core.io.ClassPathResource(path).getInputStream().readAllBytes();
            return fonts.addFont(bytes, PdfEncodings.IDENTITY_H);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 1쪽: 개요

    private static void cover(Document doc, Plan plan, int adults) {
        Table band = new Table(1).useAllAvailableWidth();
        Cell cell = new Cell().setBackgroundColor(NAVY).setBorder(Border.NO_BORDER).setPaddingTop(22).setPaddingBottom(20).setPaddingLeft(22).setPaddingRight(22);
        cell.add(new Paragraph("TRAVEL PLAN").setFontColor(new DeviceRgb(0xA9, 0xC1, 0xDD)).setFontSize(8.5f).setCharacterSpacing(2f).setMargin(0));
        cell.add(new Paragraph(plan.getTitle()).simulateBold().setFontColor(WHITE).setFontSize(22).setMarginTop(4).setMarginBottom(6));
        cell.add(new Paragraph(periodLine(plan)).setFontColor(new DeviceRgb(0xDC, 0xE6, 0xF1)).setFontSize(11).setMargin(0));
        band.addCell(cell);
        doc.add(band);

        Table grid = new Table(UnitValue.createPercentArray(new float[]{14, 36, 14, 36})).useAllAvailableWidth().setMarginTop(10);
        String[][] rows = {
                {"도시", route(plan), "동행", orDash(plan.getCompanion()) + " · 어른 " + adults + "명"},
                {"테마", orDash(plan.getTheme()), "이동 수단", orDash(plan.getTransportation())},
                {"입국", arrival(plan.getInCity(), plan.getInTime(), "도착"), "출국", arrival(plan.getOutCity(), plan.getOutTime(), "출발")},
                {"하루 일정", dailyWindow(plan), "제외 테마", orDash(plan.getExcludedThemes())},
        };
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                boolean label = i % 2 == 0;
                Cell c = new Cell().add(new Paragraph(row[i]).setMargin(0).setFontSize(label ? 8.5f : 9.5f).setFontColor(label ? GRAY : INK))
                        .setBorder(Border.NO_BORDER).setBorderBottom(new SolidBorder(LINE, 0.6f))
                        .setPaddingTop(6).setPaddingBottom(6).setPaddingLeft(label ? 8 : 4);
                if (label) c.setBackgroundColor(PAPER);
                grid.addCell(c);
            }
        }
        doc.add(grid);
    }

    // ---------------------------------------------------------------- 예상 경비

    private static void costs(Document doc, Plan plan, List<Accommodation> stays, Map<Long, Traffic> moves, int adults) {
        heading(doc, "예상 경비");

        int transit = 0;
        boolean anyTransit = false;
        for (Traffic t : moves.values()) {
            if (t.getEstimatedCost() != null && t.getEstimatedCost() > 0) {
                transit += t.getEstimatedCost();
                anyTransit = true;
            }
        }
        int lodging = 0;
        int known = 0;
        for (Accommodation a : stays) {
            if (a.getPriceYen() != null) {
                lodging += a.getPriceYen();
                known++;
            }
        }
        boolean lodgingComplete = !stays.isEmpty() && known == stays.size();
        int perPerson = transit + (int) Math.round(lodging / (double) adults);

        Table boxes = new Table(UnitValue.createPercentArray(new float[]{1, 1, 1})).useAllAvailableWidth();
        boxes.addCell(costBox("교통비 (1인)", anyTransit ? yen(transit) : "-", anyTransit ? "일정 안의 대중교통 추정 요금 합계" : "요금이 계산된 구간 없음", false));
        String lodgingSub;
        if (stays.isEmpty()) lodgingSub = "등록된 숙소 없음";
        else if (known == 0) lodgingSub = "숙박비를 확인하지 못함";
        else lodgingSub = "일행 " + adults + "명 합계" + (lodgingComplete ? "" : " · " + stays.size() + "곳 중 " + known + "곳만");
        boxes.addCell(costBox("숙박비", known > 0 ? yen(lodging) : "-", lodgingSub, false));
        String perSub = known > 0 ? "교통비 + 숙박비 ÷ " + adults + "명" + (lodgingComplete ? "" : " (확인된 숙박비만)") : "교통비만";
        boxes.addCell(costBox("1인 예상 경비", (anyTransit || known > 0) ? yen(perPerson) : "-", perSub, true));
        doc.add(boxes);

        doc.add(small("식비·입장료·공항 철도는 들어 있지 않습니다. 교통 패스를 쓰면 교통비가 줄 수 있습니다(앱 화면의 패스 추천 참고).")
                .setMarginTop(5));
    }

    private static Cell costBox(String label, String value, String sub, boolean accent) {
        Cell c = new Cell().setBorder(Border.NO_BORDER).setPadding(10)
                .setBackgroundColor(accent ? NAVY : PAPER);
        c.setBorderLeft(new SolidBorder(WHITE, 4)).setBorderRight(new SolidBorder(WHITE, 4));
        c.add(new Paragraph(label).setFontSize(8.5f).setFontColor(accent ? new DeviceRgb(0xC9, 0xD6, 0xE6) : GRAY).setMargin(0));
        c.add(new Paragraph(value).simulateBold().setFontSize(16).setFontColor(accent ? WHITE : INK).setMarginTop(3).setMarginBottom(2));
        c.add(new Paragraph(sub).setFontSize(7.5f).setFontColor(accent ? new DeviceRgb(0xC9, 0xD6, 0xE6) : GRAY).setMargin(0));
        return c;
    }

    // ---------------------------------------------------------------- 숙소

    private static void lodging(Document doc, Plan plan, List<Accommodation> stays) {
        if (stays.isEmpty()) return;
        heading(doc, "숙소");
        Table t = new Table(UnitValue.createPercentArray(new float[]{40, 24, 16, 20})).useAllAvailableWidth();
        for (String h : new String[]{"숙소", "기간", "숙박비", "근거"}) {
            t.addHeaderCell(new Cell().add(new Paragraph(h).setFontSize(8.5f).setFontColor(GRAY).setMargin(0))
                    .setBorder(Border.NO_BORDER).setBorderBottom(new SolidBorder(NAVY, 1)).setPadding(5));
        }
        for (Accommodation a : stays) {
            Cell name = cellBox().add(new Paragraph(orDash(a.getName())).simulateBold().setMargin(0));
            if (a.getAddress() != null && !a.getAddress().isBlank()) name.add(small(a.getAddress()));
            t.addCell(name);
            t.addCell(cellBox().add(new Paragraph(stayPeriod(plan, a)).setMargin(0)));
            t.addCell(cellBox().add(new Paragraph(a.getPriceYen() != null ? yen(a.getPriceYen()) : "-").setMargin(0)));
            t.addCell(cellBox().add(new Paragraph(sourceLabel(a)).setFontSize(8.5f).setFontColor(GRAY).setMargin(0)));
        }
        doc.add(t);
    }

    private static Cell cellBox() {
        return new Cell().setBorder(Border.NO_BORDER).setBorderBottom(new SolidBorder(LINE, 0.6f)).setPadding(6);
    }

    static String stayPeriod(Plan plan, Accommodation a) {
        if (a.getCheckIn() == null || a.getCheckOut() == null) return "-";
        long nights = Math.max(0, ChronoUnit.DAYS.between(a.getCheckIn(), a.getCheckOut()));
        if (plan.undated()) {
            long in = ChronoUnit.DAYS.between(plan.getStartDate(), a.getCheckIn()) + 1;
            long outDay = ChronoUnit.DAYS.between(plan.getStartDate(), a.getCheckOut()) + 1;
            return in + "일차 ~ " + outDay + "일차 (" + nights + "박)";
        }
        DateTimeFormatter f = DateTimeFormatter.ofPattern("M/d");
        return a.getCheckIn().format(f) + " ~ " + a.getCheckOut().format(f) + " (" + nights + "박)";
    }

    static String sourceLabel(Accommodation a) {
        if (a.getPriceSource() == null) return a.getPriceYen() != null ? "직접 입력" : "확인 못 함";
        switch (a.getPriceSource()) {
            case "user": return "직접 입력";
            case "rakuten": return "라쿠텐 최저가 (그 날짜 빈방)";
            case "rakuten-estimate": return "라쿠텐 표시 최저가 (추정)";
            default: return a.getPriceSource();
        }
    }

    // ---------------------------------------------------------------- AI 코멘트 · 안내

    private static void comment(Document doc, Plan plan, List<Place> places) {
        if (plan.getAiReason() == null || plan.getAiReason().isBlank()) return;
        heading(doc, "플래너 코멘트");
        // 예전에 저장한 요약에는 원래 이름이 들어 있을 수 있다
        Table box = new Table(1).useAllAvailableWidth();
        box.addCell(new Cell().setBorder(Border.NO_BORDER).setBorderLeft(new SolidBorder(TEAL, 3)).setBackgroundColor(TEAL_SOFT)
                .setPadding(10).setPaddingLeft(12)
                .add(new Paragraph(PlaceNames.localize(plan.getAiReason().trim(), places)).setFontSize(9.5f).setMultipliedLeading(1.5f).setMargin(0)));
        doc.add(box);
    }

    private static void notes(Document doc, Plan plan) {
        heading(doc, "알아 둘 점");
        List<String> lines = new ArrayList<>();
        if (plan.undated()) lines.add("날짜가 정해지지 않은 일정이라 날씨·요일별 휴무·일몰 시각은 반영하지 않았습니다. 영업시간은 요일별 전체를 적었습니다.");
        lines.add("이동 시간과 요금은 철도망·버스 노선 자료로 계산한 추정값입니다(성인 1인, IC 카드 기준). 출발 전에 운행 정보를 확인하세요.");
        lines.add("영업시간은 구글 지도 정보 기준이며 바뀔 수 있습니다.");
        lines.add("숙박비 최저가는 조식·방 크기·취소 조건에 따라 달라질 수 있고, 숙박세가 따로 붙을 수 있습니다.");
        for (String line : lines) {
            doc.add(new Paragraph().add(new Text("•  ").setFontColor(TEAL)).add(new Text(line))
                    .setFontSize(8.5f).setFontColor(GRAY).setMarginTop(1).setMarginBottom(1).setPaddingLeft(2));
        }
    }

    // ---------------------------------------------------------------- 일차별 쪽

    private static void dayPage(Document doc, Plan plan, int day, List<Itinerary> items, Map<Long, Traffic> moves) {
        LocalDate date = plan.dateOfDay(day);

        int visits = 0;
        int moveMinutes = 0;
        int fare = 0;
        for (Itinerary it : items) {
            if (isVisit(it)) visits++;
            Traffic t = it.getId() != null ? moves.get(it.getId()) : null;
            if (t != null) {
                if (t.getDurationMinutes() != null) moveMinutes += t.getDurationMinutes();
                if (t.getEstimatedCost() != null) fare += t.getEstimatedCost();
            }
        }

        Table head = new Table(UnitValue.createPercentArray(new float[]{18, 82})).useAllAvailableWidth().setMarginBottom(4);
        head.addCell(new Cell().setBackgroundColor(NAVY).setBorder(Border.NO_BORDER).setPadding(10).setVerticalAlignment(VerticalAlignment.MIDDLE)
                .add(new Paragraph("DAY").setFontSize(8).setCharacterSpacing(1.5f).setFontColor(new DeviceRgb(0xA9, 0xC1, 0xDD)).setMargin(0).setTextAlignment(TextAlignment.CENTER))
                .add(new Paragraph(String.valueOf(day)).simulateBold().setFontSize(22).setFontColor(WHITE).setMargin(0).setTextAlignment(TextAlignment.CENTER)));
        Cell right = new Cell().setBackgroundColor(NAVY_SOFT).setBorder(Border.NO_BORDER).setPadding(10).setPaddingLeft(14).setVerticalAlignment(VerticalAlignment.MIDDLE);
        String when = date != null ? date.format(DateTimeFormatter.ofPattern("M월 d일")) + " (" + weekday(date.getDayOfWeek()) + ")" : "날짜 미정";
        String city = dayCity(items);
        right.add(new Paragraph(when + (city != null ? "  ·  " + city : "")).simulateBold().setFontSize(13).setFontColor(NAVY).setMargin(0));
        List<String> stats = new ArrayList<>();
        stats.add("방문 " + visits + "곳");
        if (moveMinutes > 0) stats.add("이동 " + minutes(moveMinutes));
        if (fare > 0) stats.add("교통비 " + yen(fare));
        right.add(new Paragraph(String.join("  ·  ", stats)).setFontSize(9).setFontColor(GRAY).setMarginTop(3).setMarginBottom(0));
        head.addCell(right);
        doc.add(head);

        Table t = new Table(UnitValue.createPercentArray(new float[]{15, 85})).useAllAvailableWidth();
        for (int i = 0; i < items.size(); i++) {
            Itinerary it = items.get(i);
            Traffic move = it.getId() != null ? moves.get(it.getId()) : null;
            if (i > 0 && move != null && move.getDurationMinutes() != null && move.getDurationMinutes() > 0) {
                moveRow(t, move);
            }
            itemRow(t, it, date);
        }
        doc.add(t);
    }

    private static void moveRow(Table t, Traffic move) {
        t.addCell(new Cell().setBorder(Border.NO_BORDER).add(new Paragraph("")));
        StringBuilder head = new StringBuilder("↓  ");
        head.append(orDash(move.getTransportType())).append(" ").append(minutes(move.getDurationMinutes()));
        if (move.getEstimatedCost() != null && move.getEstimatedCost() > 0) head.append(" · ").append(yen(move.getEstimatedCost()));
        Paragraph p = new Paragraph().add(new Text(head.toString()).setFontColor(TEAL)).setFontSize(8.5f).setMargin(0);
        if (move.getPathDetails() != null && !move.getPathDetails().isBlank()) {
            p.add(new Text("   " + move.getPathDetails()).setFontColor(GRAY));
        }
        t.addCell(new Cell().setBorder(Border.NO_BORDER).setBorderLeft(new SolidBorder(TEAL_SOFT, 2)).setPaddingTop(3).setPaddingBottom(3).setPaddingLeft(10).add(p));
    }

    private static void itemRow(Table t, Itinerary it, LocalDate date) {
        Place place = it.getPlace();
        boolean pseudo = it.getCustomTitle() != null || place == null || PlanService.isPseudoCategory(place.getCategory());
        boolean visit = isVisit(it);

        Cell time = new Cell().setBorder(Border.NO_BORDER).setBorderTop(new SolidBorder(LINE, 0.6f)).setPaddingTop(8).setPaddingBottom(8);
        time.add(new Paragraph(orDash(it.getTime())).simulateBold().setFontSize(11).setFontColor(visit ? NAVY : INK).setMargin(0));
        if (it.getEndTime() != null) time.add(new Paragraph("~ " + it.getEndTime()).setFontSize(8.5f).setFontColor(GRAY).setMargin(0));
        t.addCell(time.setKeepTogether(true));

        Cell body = new Cell().setBorder(Border.NO_BORDER).setBorderTop(new SolidBorder(LINE, 0.6f)).setPaddingTop(8).setPaddingBottom(8).setPaddingLeft(10);
        body.setKeepTogether(true);
        Paragraph title = new Paragraph().setMargin(0);
        title.add(new Text(placeTitle(it)).simulateBold().setFontSize(pseudo ? 10 : 11).setFontColor(pseudo ? GRAY : INK));
        if (!pseudo) {
            String sub = PlaceDescriber.subTypeOf(place);
            if (sub != null && !sub.isBlank()) title.add(new Text("   " + sub).setFontSize(8.5f).setFontColor(TEAL));
        }
        body.add(title);

        if (pseudo) {
            if (it.getAiComment() != null && !it.getAiComment().isBlank() && !it.getAiComment().equals(placeTitle(it))) {
                body.add(new Paragraph(it.getAiComment()).setFontSize(8.5f).setFontColor(GRAY).setMarginTop(2).setMarginBottom(0));
            }
        } else {
            if (place.getNameKo() != null && !place.getNameKo().isBlank() && place.getName() != null && !place.getName().equals(place.shownName())) {
                body.add(new Paragraph(place.getName()).setFontSize(8).setFontColor(GRAY).setMarginTop(1).setMarginBottom(0));
            }
            String what = visit ? PlaceDescriber.summaryOf(place) : it.getAiComment();
            if (!visit && PlaceDescriber.summaryOf(place) != null) what = it.getAiComment() + " — " + PlaceDescriber.summaryOf(place);
            if (what != null && !what.isBlank()) {
                body.add(new Paragraph(what).setFontSize(9).setMarginTop(3).setMarginBottom(2).setMultipliedLeading(1.35f));
            }
            List<String> facts = new ArrayList<>();
            if (place.getRating() != null) {
                facts.add("★ " + String.format(Locale.ROOT, "%.1f", place.getRating())
                        + (place.getUserRatingCount() != null ? " (" + String.format("%,d", place.getUserRatingCount()) + ")" : ""));
            }
            if (place.getPhone() != null && !place.getPhone().isBlank()) facts.add("☎ " + place.getPhone());
            // 숙소·공항 줄은 무엇을 하는지만 적는다 (숙소 주소·연락처는 1쪽 숙소 표에 있다)
            if (visit) {
                if (!facts.isEmpty()) body.add(small(String.join("     ", facts)));
                if (place.getAddress() != null && !place.getAddress().isBlank()) body.add(labeled("주소", place.getAddress()));
                String hours = OpeningHours.describe(place, date);
                if (!OpeningHours.LEGACY_DEFAULT.equals(hours)) {
                    body.add(labeled("영업", date == null ? weeklyHours(hours) : stripDay(hours)));
                }
            }
        }
        t.addCell(body);
    }

    private static boolean isVisit(Itinerary it) {
        if (it.getCustomTitle() != null || it.getPlace() == null) return false;
        Place p = it.getPlace();
        if (PlanService.isPseudoCategory(p.getCategory())) return false;
        if ("숙소".equals(p.getCategory()) || "교통".equals(p.getCategory())) return false;
        String c = it.getAiComment();
        return c == null || !(c.startsWith("숙소 ") || c.startsWith("입국") || c.startsWith("출국"));
    }

    // ---------------------------------------------------------------- 쪽 아래

    private static void footer(Document doc, PdfDocument pdf, Plan plan, LocalDate today) {
        int n = pdf.getNumberOfPages();
        String left = plan.getTitle() + "  ·  " + today.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) + " 만듦";
        for (int i = 1; i <= n; i++) {
            PageSize size = new PageSize(pdf.getPage(i).getPageSize());
            doc.showTextAligned(new Paragraph(left).setFontSize(7.5f).setFontColor(GRAY), 40, 26, i, TextAlignment.LEFT, VerticalAlignment.BOTTOM, 0);
            doc.showTextAligned(new Paragraph(i + " / " + n).setFontSize(7.5f).setFontColor(GRAY), size.getWidth() - 40, 26, i, TextAlignment.RIGHT, VerticalAlignment.BOTTOM, 0);
        }
    }

    // ---------------------------------------------------------------- 작은 도구

    private static void heading(Document doc, String text) {
        doc.add(new Paragraph(text).simulateBold().setFontSize(12).setFontColor(NAVY).setMarginTop(18).setMarginBottom(6)
                .setBorderBottom(new SolidBorder(NAVY, 1)).setPaddingBottom(3));
    }

    private static Paragraph small(String text) {
        return new Paragraph(text).setFontSize(8).setFontColor(GRAY).setMarginTop(1).setMarginBottom(0);
    }

    private static Paragraph labeled(String label, String value) {
        return new Paragraph().add(new Text(label + "  ").setFontColor(AMBER)).add(new Text(value).setFontColor(GRAY))
                .setFontSize(8).setMarginTop(1).setMarginBottom(0);
    }

    static String periodLine(Plan plan) {
        int days = plan.totalDays();
        if (plan.undated()) return nightsLabel(days) + "  ·  날짜 미정";
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy.MM.dd");
        return nightsLabel(days) + "  ·  " + plan.getStartDate().format(f) + " (" + weekday(plan.getStartDate().getDayOfWeek()) + ")"
                + " ~ " + plan.getEndDate().format(f) + " (" + weekday(plan.getEndDate().getDayOfWeek()) + ")";
    }

    private static String route(Plan plan) {
        String cities = plan.getCities() != null && !plan.getCities().isBlank() ? plan.getCities() : plan.getTitle();
        return cities.replace(", ", " → ");
    }

    private static String arrival(String city, String time, String verb) {
        if (city == null || city.isBlank()) return "-";
        if (time == null || time.isBlank() || "미정".equals(time)) return city + " (시간 미정)";
        return city + "  " + time + (time.matches("\\d{1,2}:\\d{2}") ? " " + verb : "");
    }

    private static String dailyWindow(Plan plan) {
        String s = plan.getPreferredStartTime() != null ? plan.getPreferredStartTime().toString() : "09:00";
        String e = plan.getPreferredEndTime() != null ? plan.getPreferredEndTime().toString() : "22:00";
        return s.substring(0, 5) + " ~ " + e.substring(0, 5);
    }

    /** 그 날 방문지가 가장 많은 도시 */
    private static String dayCity(List<Itinerary> items) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Itinerary it : items) {
            if (!isVisit(it) || it.getPlace().getCity() == null || it.getPlace().getCity().isBlank()) continue;
            count.merge(it.getPlace().getCity(), 1, Integer::sum);
        }
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** 요일별 영업시간: 매일 같으면 "매일 09:00~17:00", 다르면 요일마다 한 줄 */
    static String weeklyHours(String text) {
        String[] days = text.split("\\|");
        java.util.Set<String> distinct = new java.util.LinkedHashSet<>();
        for (String d : days) distinct.add(stripDay(d.trim()));
        if (days.length == 7 && distinct.size() == 1) return "매일 " + distinct.iterator().next();
        return java.util.Arrays.stream(days).map(String::trim).collect(Collectors.joining("\n"));
    }

    /** "월요일: 09:00~17:00" → "09:00~17:00" */
    private static String stripDay(String line) {
        int colon = line.indexOf(':');
        if (colon > 0 && colon <= 12 && !Character.isDigit(line.charAt(colon - 1))) return line.substring(colon + 1).trim();
        return line;
    }

    private static String weekday(DayOfWeek d) {
        return d.getDisplayName(TextStyle.SHORT, Locale.KOREAN);
    }

    static String yen(int v) {
        return String.format("%,d", v) + "엔";
    }

    static String minutes(Integer m) {
        if (m == null) return "";
        if (m < 60) return m + "분";
        return m / 60 + "시간" + (m % 60 > 0 ? " " + m % 60 + "분" : "");
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
