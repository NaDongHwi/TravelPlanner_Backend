package com.travel.planner.scheduler;

import com.travel.planner.repository.LogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class LogCleanupScheduler {

    private final LogRepository logRepository;

    // 매일 새벽 3시에 실행: 7일이 지난 오래된 에러 로그 자동 삭제
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void cleanupOldLogs() {
        LocalDateTime oneWeekAgo = LocalDateTime.now().minusDays(7);
        logRepository.deleteByErrorTimeBefore(oneWeekAgo);
        System.out.println("7일 이상 경과한 오래된 시스템 로그가 성공적으로 정리되었습니다.");
    }
}