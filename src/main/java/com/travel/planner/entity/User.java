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

    @Column(nullable = false)
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
}