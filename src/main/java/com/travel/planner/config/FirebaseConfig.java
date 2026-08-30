package com.travel.planner.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;

@Configuration
public class FirebaseConfig {

    @PostConstruct
    public void init() {
        try {
            // 나중에 Firebase 콘솔에서 발급받은 키 파일을 src/main/resources 안에 넣어야 함.
            InputStream serviceAccount = getClass().getResourceAsStream("/firebase-service-account.json");

            if (serviceAccount == null) {
                System.err.println("Firebase 인증 키 파일이 없어 FCM을 초기화할 수 없습니다. (테스트 환경에서는 정상 작동합니다)");
                return;
            }

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options);
                System.out.println("Firebase Cloud Messaging (FCM) 초기화 성공!");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}