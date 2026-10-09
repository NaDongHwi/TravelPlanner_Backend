package com.travel.planner.service;

import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.Place;
import com.travel.planner.util.AirportDirectory;
import com.travel.planner.util.FixedScheduleCodec;
import com.travel.planner.util.PlaceKind;
import com.travel.planner.util.StringCryptoConverter;
import com.travel.planner.util.ThemeVocabulary;
import com.travel.planner.util.TravelTimeEstimator;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 엔진 주변 로직(AI 응답 해석, 공항 결정, 이동 시간, 테마 판정, 암호화) 단위 테스트. 스프링 컨텍스트 불필요. */
class SupportLogicTest {

    // ---------------------------------------------------------------- 인리치먼트 응답 해석

    @Test
    void enrichmentAnswerWithTwoFieldsIsStillSaved() {
        Place cafe = place("테스트 카페", "식음");
        AdminAsyncService.ParsedAttributes parsed = AdminAsyncService.parseAttributes("쇼핑,서브컬쳐|실내", cafe);
        assertNotNull(parsed, "프롬프트 예전 형식(2칸) 응답도 버리지 않는다");
        assertEquals("쇼핑,서브컬쳐", parsed.theme);
        assertEquals("실내", parsed.placeType);
        assertEquals(45, parsed.duration);   // 체류시간이 없으면 유형(카페) 기본값
    }

    @Test
    void enrichmentAnswerWithThreeFields() {
        AdminAsyncService.ParsedAttributes parsed = AdminAsyncService.parseAttributes(" 온천, 힐링 | 실외 | 120분 ", place("스파", "관광지"));
        assertEquals("온천,힐링", parsed.theme);
        assertEquals("실외", parsed.placeType);
        assertEquals(120, parsed.duration);
    }

    @Test
    void enrichmentAnswerOutsideVocabularyIsRejected() {
        Place p = place("어딘가", "관광지");
        assertNull(AdminAsyncService.parseAttributes("신나는곳|실내|90", p));
        assertNull(AdminAsyncService.parseAttributes("맛집", p));
        assertNull(AdminAsyncService.parseAttributes(null, p));
        assertEquals("맛집,카페,사진", AdminAsyncService.parseAttributes("맛집,기타,카페,사진,야경|실내|60", p).theme);
    }

    // ---------------------------------------------------------------- 공항

    @Test
    void airportIsResolvedFromCityOrAirportName() {
        assertEquals("KIX", AirportDirectory.resolve("오사카", null).airport.code);
        assertEquals("CTS", AirportDirectory.resolve("삿포로", null).airport.code);
        assertEquals("FUK", AirportDirectory.resolve("후쿠오카", null).airport.code);
        assertEquals("HND", AirportDirectory.resolve("도쿄 하네다", null).airport.code);   // 공항 이름이 도시 이름보다 우선
        assertEquals("NRT", AirportDirectory.resolve("도쿄", null).airport.code);
        assertTrue(AirportDirectory.resolve("도쿄", null).ambiguous);                      // 나리타/하네다 모호 → 경고 대상
        assertFalse(AirportDirectory.resolve("하네다 공항", null).ambiguous);
        assertNull(AirportDirectory.resolve("okayama", null));                             // "oka"(나하)로 오인하지 않는다
        assertNull(AirportDirectory.resolve(" ", null));
    }

    // ---------------------------------------------------------------- 이동 시간

    @Test
    void travelTimesAreRealistic() {
        int nambaToKix = TravelTimeEstimator.minutes(34.6660, 135.5020, 34.4342, 135.2328, "대중교통");
        int osakaToKyoto = TravelTimeEstimator.minutes(34.6687, 135.5013, 35.0037, 135.7788, "대중교통");
        int tokyoToOsaka = TravelTimeEstimator.minutes(35.6812, 139.7671, 34.7025, 135.4959, "대중교통");
        int nextDoor = TravelTimeEstimator.minutes(34.6687, 135.5013, 34.6710, 135.5030, "대중교통");
        assertTrue(nambaToKix >= 50 && nambaToKix <= 80, "난바→간사이공항 " + nambaToKix);
        assertTrue(osakaToKyoto >= 55 && osakaToKyoto <= 90, "오사카→교토 " + osakaToKyoto);
        assertTrue(tokyoToOsaka >= 180 && tokyoToOsaka <= 260, "도쿄→오사카 " + tokyoToOsaka);
        assertTrue(nextDoor <= 10, "300m " + nextDoor);
    }

    // ---------------------------------------------------------------- 테마·유형 판정

    @Test
    void themesAreNormalizedAndInferred() {
        assertEquals("맛집", ThemeVocabulary.normalize("식도락"));
        assertEquals("관광", ThemeVocabulary.normalize("필수 관광지"));
        assertNull(ThemeVocabulary.normalize("아무말"));
        assertTrue(ThemeVocabulary.inferredThemes(place("히메지성", "관광지")).contains("문화"));
        assertTrue(ThemeVocabulary.inferredThemes(place("이치란 라멘", "식음")).contains("맛집"));
    }

    @Test
    void themeParkDetection() {
        assertEquals(PlaceKind.SHOPPING, PlaceKind.of(place("디즈니 스토어 시부야점", "쇼핑")));
        Place usj = place("유니버설 스튜디오 재팬", "관광지");
        assertEquals(PlaceKind.THEME_PARK, PlaceKind.of(usj));
        assertEquals(480, new PlanService().calculateDwellTime(usj, new PlanRequest()));
    }

    // ---------------------------------------------------------------- 고정 일정 저장/복원

    @Test
    void fixedSchedulesSurviveRoundTrip() {
        PlanRequest.FixedScheduleInput f = new PlanRequest.FixedScheduleInput();
        f.setName("공연");
        f.setStartTime(LocalTime.of(14, 0));
        f.setEndTime(LocalTime.of(15, 30));
        f.setDate(LocalDate.of(2026, 7, 3));
        f.setLatitude(34.67);
        f.setLongitude(135.5);

        List<PlanRequest.FixedScheduleInput> restored = FixedScheduleCodec.fromJson(FixedScheduleCodec.toJson(List.of(f)));
        assertEquals(1, restored.size());
        assertEquals("공연", restored.get(0).getName());
        assertEquals(LocalTime.of(15, 30), restored.get(0).getEndTime());
        assertEquals(LocalDate.of(2026, 7, 3), restored.get(0).getDate());
        assertNull(restored.get(0).getDayNumber());
        assertTrue(FixedScheduleCodec.fromJson("깨진 값").isEmpty());
        assertNull(FixedScheduleCodec.toJson(null));
    }

    // ---------------------------------------------------------------- 개인정보 암호화

    @Test
    void encryptionIsRandomizedAndReadsLegacyData() throws Exception {
        String jwtSecret = "my-super-secret-jwt-key-for-planner-app-1234567890";
        StringCryptoConverter converter = new StringCryptoConverter();
        converter.setJwtSecret(jwtSecret);
        converter.setCryptoSecret("");
        converter.setLegacySecret("");

        String first = converter.convertToDatabaseColumn("여성");
        String second = converter.convertToDatabaseColumn("여성");
        assertTrue(first.startsWith("v2:"));
        assertFalse(first.equals(second), "같은 값이라도 암호문은 매번 달라야 한다");
        assertEquals("여성", converter.convertToEntityAttribute(first));
        assertEquals("여성", converter.convertToEntityAttribute(second));

        // 예전 방식(AES/ECB, jwt.secret 앞 16바이트)으로 저장된 값도 그대로 읽힌다
        Cipher legacy = Cipher.getInstance("AES");
        legacy.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOf(jwtSecret.getBytes(StandardCharsets.UTF_8), 16), "AES"));
        String legacyValue = Base64.getEncoder().encodeToString(legacy.doFinal("20대".getBytes(StandardCharsets.UTF_8)));
        assertEquals("20대", converter.convertToEntityAttribute(legacyValue));

        // 암호화 이전의 평문 데이터
        assertEquals("미정", converter.convertToEntityAttribute("미정"));
    }

    @Test
    void encryptedDataSurvivesSecretChanges() throws Exception {
        String oldJwtSecret = "old-jwt-secret-that-was-used-at-first-0123456789";
        StringCryptoConverter converter = new StringCryptoConverter();
        converter.setJwtSecret(oldJwtSecret);
        converter.setCryptoSecret("");
        converter.setLegacySecret("");

        String savedBeforeAnyChange = converter.convertToDatabaseColumn("30대");
        Cipher ecb = Cipher.getInstance("AES");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOf(oldJwtSecret.getBytes(StandardCharsets.UTF_8), 16), "AES"));
        String savedByOldCode = Base64.getEncoder().encodeToString(ecb.doFinal("남성".getBytes(StandardCharsets.UTF_8)));

        // 1) app.crypto.secret 을 나중에 추가해도 기존 값이 읽힌다
        converter.setCryptoSecret("separate-db-key");
        assertEquals("30대", converter.convertToEntityAttribute(savedBeforeAnyChange));
        String savedWithCryptoSecret = converter.convertToDatabaseColumn("여성");

        // 2) jwt.secret 을 바꾸고 이전 값을 app.crypto.legacy-secret 에 넣으면 전부 계속 읽힌다
        converter.setJwtSecret("brand-new-jwt-secret-after-rotation-9876543210");
        converter.setLegacySecret(oldJwtSecret);
        assertEquals("30대", converter.convertToEntityAttribute(savedBeforeAnyChange));
        assertEquals("남성", converter.convertToEntityAttribute(savedByOldCode));
        assertEquals("여성", converter.convertToEntityAttribute(savedWithCryptoSecret));

        // 3) 이전 값을 알려 주지 않으면 조용히 깨진 값을 돌려주지 않고 오류로 알린다
        converter.setLegacySecret("");
        converter.setCryptoSecret("");
        boolean failed = false;
        try {
            converter.convertToEntityAttribute(savedBeforeAnyChange);
        } catch (RuntimeException e) {
            failed = true;
        }
        assertTrue(failed, "키가 맞지 않으면 예외가 나야 한다");

        // 다른 테스트에 영향이 없도록 초기화
        converter.setJwtSecret("DefaultSecret123");
    }

    private static Place place(String name, String category) {
        Place p = new Place();
        p.setName(name);
        p.setCategory(category);
        return p;
    }
}
