package com.travel.planner.controller;

import com.travel.planner.dto.LongTripSettingsDto;
import com.travel.planner.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "2. 인증 및 회원 API")
public class UserController {

    private final UserService userService;

    @PutMapping("/me/fcm-token")
    @Operation(summary = "푸시 알림 토큰 등록", description = "앱이 발급받은 FCM 토큰을 저장합니다. 기상 악화 알림을 받으려면 필요합니다. 요청 본문: {\"fcmToken\": \"...\"}")
    public ResponseEntity<String> updateFcmToken(Authentication authentication, @RequestBody Map<String, String> body) {
        userService.updateFcmToken(authentication.getName(), body == null ? null : body.get("fcmToken"));
        return ResponseEntity.ok("푸시 알림 토큰이 저장되었습니다.");
    }

    @GetMapping("/me/long-trip-settings")
    @Operation(summary = "마이페이지: 장기 일정 설정 보기",
            description = "15일 이상 여행의 여유일 간격과 그날 관광 시간 상한. 바꾼 적이 없으면 기본값(살아보기 5일마다·관광 0시간, 투어 7일마다·관광 3시간)이 나옵니다.")
    public LongTripSettingsDto getLongTripSettings(Authentication authentication) {
        return userService.getLongTripSettings(authentication.getName());
    }

    @PutMapping("/me/long-trip-settings")
    @Operation(summary = "마이페이지: 장기 일정 설정 바꾸기",
            description = "보낸 칸만 바뀝니다. 간격은 0(없음) 또는 2~30일, 관광 시간은 0~10시간(0.5 단위). "
                    + "예: {\"liveRestEveryDays\": 4, \"liveRestSightHours\": 1.5, \"tourLightEveryDays\": 6, \"tourLightSightHours\": 2}. "
                    + "바꾼 값은 다음에 만드는 일정부터 적용됩니다.")
    public LongTripSettingsDto updateLongTripSettings(Authentication authentication, @RequestBody LongTripSettingsDto body) {
        return userService.updateLongTripSettings(authentication.getName(), body);
    }

    @DeleteMapping("/me/long-trip-settings")
    @Operation(summary = "마이페이지: 장기 일정 설정을 기본값으로 되돌리기")
    public LongTripSettingsDto resetLongTripSettings(Authentication authentication) {
        return userService.resetLongTripSettings(authentication.getName());
    }
}
