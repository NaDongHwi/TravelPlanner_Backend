package com.travel.planner.service;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Plan;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PdfExportService {

    public byte[] generatePlanPdf(Plan plan) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            // 1. PDF 작성기 초기화
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document document = new Document(pdf);

            org.springframework.core.io.ClassPathResource fontResource = new org.springframework.core.io.ClassPathResource("fonts/NanumGothic.ttf");
            byte[] fontBytes = fontResource.getInputStream().readAllBytes();
            PdfFont koreanFont = PdfFontFactory.createFont(fontBytes, PdfEncodings.IDENTITY_H);

            document.setFont(koreanFont);

            // 2. 타이틀
            Text titleText = new Text(plan.getTitle() + " 일정표").simulateBold();
            Paragraph title = new Paragraph(titleText);
            title.setTextAlignment(TextAlignment.CENTER);
            title.setFontSize(24);
            title.setMarginBottom(10);
            document.add(title);

            // 3. 여행 기본 정보
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일");
            String period = plan.getStartDate().format(formatter) + " ~ " + plan.getEndDate().format(formatter);

            Paragraph periodPara = new Paragraph("여행 기간: " + period);
            periodPara.setFontSize(12);
            document.add(periodPara);

            Paragraph themePara = new Paragraph("여행 테마: " + (plan.getTheme() != null ? plan.getTheme() : "자유 여행"));
            themePara.setFontSize(12);
            document.add(themePara);

            // AI 추천 사유 추가
            if (plan.getAiReason() != null) {
                Text aiReasonText = new Text("\n[AI 플래너의 추천 코멘트]\n" + plan.getAiReason()).simulateItalic();
                Paragraph aiReason = new Paragraph(aiReasonText);
                aiReason.setFontSize(11);
                aiReason.setMarginBottom(20);
                document.add(aiReason);
            }

            // 4. 일차(Day)별로 타임라인을 그룹화
            Map<Integer, List<Itinerary>> groupedItineraries = plan.getItineraries().stream()
                    .collect(Collectors.groupingBy(Itinerary::getDayNumber));

            // 5. 각 일차별로 표(Table)를 그려서 문서에 추가
            for (Integer day : groupedItineraries.keySet().stream().sorted().toList()) {

                // Day 헤더
                Text dayHeaderText = new Text("\nDay " + day).simulateBold();
                Paragraph dayHeader = new Paragraph(dayHeaderText);
                dayHeader.setFontSize(16);
                dayHeader.setFontColor(ColorConstants.BLUE);
                dayHeader.setMarginBottom(5);
                document.add(dayHeader);

                // 3개의 열(시간, 장소, 설명)을 가진 테이블 생성
                Table table = new Table(UnitValue.createPercentArray(new float[]{15, 30, 55})).useAllAvailableWidth();

                // 테이블 헤더
                Paragraph timeHeader = new Paragraph(new Text("시간").simulateBold());
                Paragraph placeHeader = new Paragraph(new Text("방문 장소").simulateBold());
                Paragraph descHeader = new Paragraph(new Text("상세 설명").simulateBold());

                table.addHeaderCell(new Cell().add(timeHeader).setBackgroundColor(ColorConstants.LIGHT_GRAY).setTextAlignment(TextAlignment.CENTER));
                table.addHeaderCell(new Cell().add(placeHeader).setBackgroundColor(ColorConstants.LIGHT_GRAY).setTextAlignment(TextAlignment.CENTER));
                table.addHeaderCell(new Cell().add(descHeader).setBackgroundColor(ColorConstants.LIGHT_GRAY).setTextAlignment(TextAlignment.CENTER));

                // 테이블 내용 채우기 (시간 순 정렬)
                List<Itinerary> dayItineraries = groupedItineraries.get(day);
                dayItineraries.sort(java.util.Comparator.comparing(Itinerary::getSequence));

                for (Itinerary iti : dayItineraries) {
                    Paragraph timeP = new Paragraph(iti.getTime());
                    Paragraph placeP = new Paragraph(iti.getPlace().getName());
                    Paragraph descP = new Paragraph(iti.getAiComment());

                    table.addCell(new Cell().add(timeP).setTextAlignment(TextAlignment.CENTER));
                    table.addCell(new Cell().add(placeP).setTextAlignment(TextAlignment.CENTER));
                    table.addCell(new Cell().add(descP));
                }

                document.add(table);
            }

            // 6. 문서 닫기
            document.close();

        } catch (Exception e) {
            throw new RuntimeException("PDF 문서 생성 중 오류가 발생했습니다.", e);
        }

        return baos.toByteArray();
    }
}