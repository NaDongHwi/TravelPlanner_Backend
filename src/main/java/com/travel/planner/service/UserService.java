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
    private final JwtUtil jwtUtil; // 진짜 토큰을 찍어내는 인쇄소

    // 1. 회원가입 로직
    public String registerUser(SignupRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            return "이미 가입된 이메일입니다.";
        }

        User newUser = new User();
        newUser.setEmail(request.getEmail());
        newUser.setPassword(passwordEncoder.encode(request.getPassword()));
        newUser.setGender(request.getGender());
        newUser.setAgeGroup(request.getAgeGroup());

        // 자체 가입자는 local로 명시
        newUser.setProvider("local");

        userRepository.save(newUser);
        return "회원가입 완료! (비밀번호가 안전하게 암호화되어 저장되었습니다.)";
    }

    public String login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("가입되지 않은 이메일입니다."));

        // 소셜 계정으로 가입한 유저가 로컬로 로그인 시도 시 방어
        if (!"local".equals(user.getProvider())) {
            throw new IllegalArgumentException(user.getProvider() + " 소셜 계정으로 로그인해주세요.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return "비밀번호가 일치하지 않습니다.";
        }

        return jwtUtil.generateToken(user.getEmail());
    }

    @org.springframework.transaction.annotation.Transactional
    public String deleteAccount(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        userRepository.delete(user);
        return "회원 탈퇴 및 개인정보 파기가 완료되었습니다.";
    }
}