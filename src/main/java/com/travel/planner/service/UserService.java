package com.travel.planner.service;

import com.travel.planner.dto.LoginRequest;
import com.travel.planner.dto.SignupRequest;
import com.travel.planner.entity.User;
import com.travel.planner.repository.UserRepository;
import com.travel.planner.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil; // 💡 진짜 토큰을 찍어내는 인쇄소 추가!

    // 1. 회원가입 로직
    public String registerUser(SignupRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            return "❌ 이미 가입된 이메일입니다.";
        }

        User newUser = new User();
        newUser.setEmail(request.getEmail());

        // 비밀번호 암호화 저장
        String encryptedPassword = passwordEncoder.encode(request.getPassword());
        newUser.setPassword(encryptedPassword);

        newUser.setGender(request.getGender());
        newUser.setAgeGroup(request.getAgeGroup());

        userRepository.save(newUser);

        return "✅ 회원가입 완료! (비밀번호가 안전하게 암호화되어 저장되었습니다.)";
    }

    // 2. 로그인 및 JWT 발급 로직
    public String login(LoginRequest request) {
        // 이메일 확인
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("❌ 가입되지 않은 이메일입니다."));

        // 비밀번호 확인
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return "❌ 비밀번호가 일치하지 않습니다.";
        }

        // JwtUtil을 사용해 암호화된 토큰을 발급합니다.
        String token = jwtUtil.generateToken(user.getEmail());

        return token;
    }
}