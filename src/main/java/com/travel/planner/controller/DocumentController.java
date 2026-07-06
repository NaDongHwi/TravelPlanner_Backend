package com.travel.planner.controller;

import com.travel.planner.entity.Plan;
import com.travel.planner.repository.PlanRepository;
import com.travel.planner.service.PdfExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "4. 문서 서비스 API", description = "여행 일정 오프라인 문서(PDF) 변환 (보고서 DOC-001)")
public class DocumentController {

    private final PlanRepository planRepository;
    private final PdfExportService pdfExportService;

    @GetMapping("/pdf/{planId}")
    @Operation(summary = "여행 일정 PDF 다운로드", description = "생성된 타임라인을 오프라인용 PDF 문서 파일로 다운로드합니다.")
    public ResponseEntity<byte[]> exportToPdf(@PathVariable Long planId) {

        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("해당 일정을 찾을 수 없습니다."));

        byte[] pdfBytes = pdfExportService.generatePlanPdf(plan);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);

        String fileName = plan.getTitle().replace(" ", "_") + "_일정표.pdf";
        headers.setContentDispositionFormData("attachment", fileName);
        headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

        return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
    }
}