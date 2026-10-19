package com.travel.planner.entity;

import com.travel.planner.util.StringCryptoConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    // 소셜 회원은 비밀번호가 없으므로 nullable 허용
    @Column(length = 500)
    private String password;

    @Convert(converter = StringCryptoConverter.class) // DB 저장 시 자동 암호화
    @Column(nullable = false)
    private String gender;

    @Convert(converter = StringCryptoConverter.class) // DB 저장 시 자동 암호화
    @Column(nullable = false)
    private String ageGroup;

    // 푸시 알림을 위한 FCM 토큰 필드 추가
    @Column(length = 500)
    private String fcmToken;

    // 가입 경로 (local, google, kakao)
    @Column(nullable = false)
    private String provider;

    // 소셜 서비스의 고유 식별자 ID
    @Column
    private String providerId;

    // ---- 마이페이지 "장기 일정" 설정 (비어 있으면 기본값: 살아보기 5일마다 관광 없이, 투어 7일마다 관광 3시간) ----

    /** 살아보기 여유일 간격(일). 0 이면 여유일 없음 */
    @Column
    private Integer liveRestEveryDays;

    /** 살아보기 여유일의 관광 시간 상한(분) */
    @Column
    private Integer liveRestSightMinutes;

    /** 투어 가벼운 날 간격(일). 0 이면 없음 */
    @Column
    private Integer tourLightEveryDays;

    /** 투어 가벼운 날의 관광 시간 상한(분) */
    @Column
    private Integer tourLightSightMinutes;
}
