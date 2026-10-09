package com.travel.planner.controller;

import com.travel.planner.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
}
