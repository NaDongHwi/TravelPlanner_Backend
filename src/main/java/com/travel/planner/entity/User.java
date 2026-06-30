package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class User {

    @Id // 이 필드를 고유한 번호(Primary Key)로 쓰겠다는 뜻입니다.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 소셜 로그인 아이디나 이메일을 저장할 칸입니다.
    @Column(nullable = false, unique = true)
    private String email;

    // 보고서 DAR-001 요구사항 반영: 성별과 나이는 필수(nullable = false)
    @Column(nullable = false)
    private String gender;

    @Column(nullable = false)
    private Integer age;

    // 닉네임
    @Column(nullable = false)
    private String nickname;
}