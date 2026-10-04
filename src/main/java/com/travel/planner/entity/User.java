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
}