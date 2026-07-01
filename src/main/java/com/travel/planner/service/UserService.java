package com.travel.planner.service;

import com.travel.planner.dto.SignupRequest;
import com.travel.planner.entity.User;
import com.travel.planner.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public String registerUser(SignupRequest request) {
        // 1. 이메일 중복 검사 (이미 가입된 사람이면 튕겨냅니다)
        if (userRepository.existsByEmail(request.getEmail())) {
            return "❌ 이미 가입된 이메일입니다.";
        }

        // 2. 프론트엔드에서 온 DTO 데이터를 실제 DB 엔티티(User)로 옮겨 담습니다.
        User newUser = new User();
        newUser.setEmail(request.getEmail());
        newUser.setPassword(request.getPassword()); // (※ 주의: 실무에서는 나중에 이 비밀번호를 암호화해서 넣어야 합니다!)
        newUser.setGender(request.getGender());
        newUser.setAgeGroup(request.getAgeGroup());

        // 3. DB에 영구 저장!
        userRepository.save(newUser);

        return "✅ 회원가입 완료! DB에 안전하게 저장되었습니다.";
    }
}