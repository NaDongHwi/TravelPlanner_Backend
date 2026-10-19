package com.travel.planner.service;

import com.travel.planner.dto.LoginRequest;
import com.travel.planner.dto.SignupRequest;
import com.travel.planner.entity.User;
import com.travel.planner.repository.UserRepository;
import com.travel.planner.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil; // 진짜 토큰을 찍어내는 인쇄소
    private final PlanPersistenceService planPersistenceService;

    // 1. 회원가입 로직
    public String registerUser(SignupRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            // 실패를 200 OK 본문으로 돌려주면 프론트가 성공과 구분할 수 없다 → 409
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
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

    /**
     * 로그인. 성공하면 JWT 문자열을 그대로 돌려준다(기존 응답 형식 유지).
     * 실패는 401 로 응답한다. 이전에는 "비밀번호가 일치하지 않습니다." 문자열이 200 OK 로 나가
     * 프론트가 그 문장을 토큰으로 저장할 수 있었다.
     */
    public String login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다."));

        // 소셜 계정으로 가입한 유저가 로컬로 로그인 시도 시 방어
        if (!"local".equals(user.getProvider())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, user.getProvider() + " 소셜 계정으로 로그인해주세요.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다.");
        }

        return jwtUtil.generateToken(user.getEmail());
    }

    @org.springframework.transaction.annotation.Transactional
    public String deleteAccount(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        // 여행 계획(일정·이동 정보·숙소)을 먼저 지운다. 사용자만 지우면 FK 제약으로 탈퇴가 실패할 수 있다.
        planPersistenceService.deletePlansOfUser(email);
        userRepository.delete(user);
        return "회원 탈퇴 및 개인정보 파기가 완료되었습니다.";
    }

    /** 푸시 알림용 FCM 토큰 등록/갱신 (빈 값이면 해제) */
    @org.springframework.transaction.annotation.Transactional
    public void updateFcmToken(String email, String fcmToken) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.setFcmToken(fcmToken == null || fcmToken.isBlank() ? null : fcmToken.trim());
        userRepository.save(user);
    }

    // ---------------------------------------------------------------- 마이페이지 "장기 일정" 설정

    /** 저장된 설정 (빈 칸은 기본값) */
    public static com.travel.planner.util.TripProfile.PaceSettings paceOf(User user) {
        if (user == null) return com.travel.planner.util.TripProfile.PaceSettings.DEFAULT;
        return new com.travel.planner.util.TripProfile.PaceSettings(user.getLiveRestEveryDays(), user.getLiveRestSightMinutes(),
                user.getTourLightEveryDays(), user.getTourLightSightMinutes());
    }

    private static boolean usingDefaults(User user) {
        return user.getLiveRestEveryDays() == null && user.getLiveRestSightMinutes() == null
                && user.getTourLightEveryDays() == null && user.getTourLightSightMinutes() == null;
    }

    public com.travel.planner.dto.LongTripSettingsDto getLongTripSettings(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        return com.travel.planner.dto.LongTripSettingsDto.of(paceOf(user), usingDefaults(user));
    }

    /** 보낸 칸만 바꾼다. 범위를 벗어나면 400 */
    @org.springframework.transaction.annotation.Transactional
    public com.travel.planner.dto.LongTripSettingsDto updateLongTripSettings(String email, com.travel.planner.dto.LongTripSettingsDto body) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (body != null) {
            if (body.getLiveRestEveryDays() != null) user.setLiveRestEveryDays(checkEvery("살아보기 여유일 간격", body.getLiveRestEveryDays()));
            if (body.getLiveRestSightHours() != null) user.setLiveRestSightMinutes(checkHours("살아보기 여유일 관광 시간", body.getLiveRestSightHours()));
            if (body.getTourLightEveryDays() != null) user.setTourLightEveryDays(checkEvery("투어 가벼운 날 간격", body.getTourLightEveryDays()));
            if (body.getTourLightSightHours() != null) user.setTourLightSightMinutes(checkHours("투어 가벼운 날 관광 시간", body.getTourLightSightHours()));
        }
        userRepository.save(user);
        return com.travel.planner.dto.LongTripSettingsDto.of(paceOf(user), usingDefaults(user));
    }

    /** 기본값으로 되돌린다 */
    @org.springframework.transaction.annotation.Transactional
    public com.travel.planner.dto.LongTripSettingsDto resetLongTripSettings(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.setLiveRestEveryDays(null);
        user.setLiveRestSightMinutes(null);
        user.setTourLightEveryDays(null);
        user.setTourLightSightMinutes(null);
        userRepository.save(user);
        return com.travel.planner.dto.LongTripSettingsDto.of(paceOf(user), true);
    }

    static int checkEvery(String label, int days) {
        if (!com.travel.planner.util.TripProfile.PaceSettings.validEvery(days)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "은 0(없음) 또는 2~30일이어야 합니다: " + days);
        }
        return days;
    }

    /** 시간 → 분. 0.5시간 단위로 받는다 */
    static int checkHours(String label, double hours) {
        if (Double.isNaN(hours) || Math.abs(hours * 2 - Math.rint(hours * 2)) > 1e-9) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "은 0.5시간 단위로 적어 주세요: " + hours);
        }
        int minutes = (int) Math.round(hours * 60);
        if (!com.travel.planner.util.TripProfile.PaceSettings.validSightMinutes(minutes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "은 0~10시간이어야 합니다: " + hours);
        }
        return minutes;
    }
}
