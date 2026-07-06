package com.travel.planner.controller;

import com.travel.planner.dto.SignupRequest;
import com.travel.planner.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor // 스프링 부트가 UserService를 자동으로 연결해 줍니다.
@Tag(name = "2. 인증 및 회원 API", description = "회원가입, 로그인 등 사용자 인증 API (담당: 나동휘)")
public class AuthController {

    private final UserService userService;

    @PostMapping("/signup")
    @Operation(summary = "회원가입 요청", description = "사용자의 이메일, 비밀번호, 성별, 연령대 등 기본 정보를 DB에 저장합니다.")
    public String signup(@Valid @RequestBody SignupRequest request) {
        // UserService에게 넘겨서 DB에 저장시키고 그 결과를 반환합니다.
        return userService.registerUser(request);
    }

    @PostMapping("/login")
    @io.swagger.v3.oas.annotations.Operation(summary = "로그인 요청", description = "이메일과 비밀번호를 확인한 후, 성공 시 로그인 증명서(JWT 토큰)를 발급합니다.")
    public String login(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody com.travel.planner.dto.LoginRequest request) {
        return userService.login(request);
    }

    @PostMapping("/withdraw")
    @Operation(summary = "회원 탈퇴", description = "토큰을 확인 후 회원의 모든 DB 정보를 파기합니다.")
    public String withdraw(org.springframework.security.core.Authentication authentication) {
        return userService.deleteAccount(authentication.getName());
    }
}