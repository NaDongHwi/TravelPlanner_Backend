package com.travel.planner.transit;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/**
 * 가나(히라가나·가타카나) 읽기를 국립국어원 외래어 표기법(일본어)에 따라 한글로 옮긴다.
 * GTFS-JP 의 정류장 읽기(ja-Hrkt)를 한국어 표기로 바꾸는 데 쓴다. AI 를 쓰지 않으므로 같은 입력은 항상 같은 결과가 나온다.
 *
 *  - 어두의 か·た·ち·きゃ·ちゃ 행은 예사소리(가·다·지·갸·자), 어중·어말은 거센소리(카·타·치·캬·차)
 *  - っ 은 ㅅ 받침, ん 은 ㄴ 받침
 *  - 장모음은 따로 적지 않는다 (とうきょう → 도쿄, おおさか → 오사카). 단 えい 는 "에이" 그대로
 * 한계: 띄어쓰기가 없는 읽기에서는 낱말 경계를 알 수 없어, "みずうみ(미즈우미)"처럼 낱말이 う 로 시작하는 경우에도 장모음으로 보고 줄인다.
 */
public final class KanaHangul {

    private KanaHangul() {}

    // 초성 번호 (유니코드 한글 조합 순서)
    private static final int G = 0, N = 2, D = 3, R = 5, M = 6, B = 7, S = 9, SS = 10, O = 11, J = 12, CH = 14, K = 15, T = 16, P = 17, H = 18;
    // 중성 번호
    private static final int A = 0, YA = 2, E = 5, YE = 7, OH = 8, WA = 9, WE = 10, YO = 12, U = 13, WO = 14, WI = 16, YU = 17, EU = 18, I = 20;

    /** 가나 한 글자(또는 요음 두 글자) → {어두 초성, 어중 초성, 중성, 모음 계열(a/i/u/e/o)} */
    private static final Map<String, int[]> TABLE = new HashMap<>();

    private static void put(String kana, int head, int mid, int vowel, char series) {
        TABLE.put(kana, new int[]{head, mid, vowel, series});
    }

    private static void row(String kana, int head, int mid) {
        int[] vowels = {A, I, U, E, OH};
        char[] series = {'a', 'i', 'u', 'e', 'o'};
        for (int i = 0; i < 5; i++) {
            put(String.valueOf(kana.charAt(i)), head, mid, vowels[i], series[i]);
        }
    }

    private static void yoon(char base, int head, int mid, boolean plainVowel) {
        // きゃ 갸/캬 처럼 ㅑㅠㅛ 를 쓰는 행과, しゃ 샤·じゃ 자·ちゃ 차 처럼 행에 따라 다른 것
        put(base + "ゃ", head, mid, plainVowel ? A : YA, 'a');
        put(base + "ゅ", head, mid, plainVowel ? U : YU, 'u');
        put(base + "ょ", head, mid, plainVowel ? OH : YO, 'o');
    }

    static {
        row("あいうえお", O, O);
        row("かきくけこ", G, K);
        row("さしすせそ", S, S);
        row("たちつてと", D, T);
        row("なにぬねの", N, N);
        row("はひふへほ", H, H);
        row("まみむめも", M, M);
        row("らりるれろ", R, R);
        row("がぎぐげご", G, G);
        row("ざじずぜぞ", J, J);
        row("だぢづでど", D, D);
        row("ばびぶべぼ", B, B);
        row("ぱぴぷぺぽ", P, P);
        put("や", O, O, YA, 'a');
        put("ゆ", O, O, YU, 'u');
        put("よ", O, O, YO, 'o');
        put("わ", O, O, WA, 'a');
        put("を", O, O, OH, 'o');
        put("ゐ", O, O, I, 'i');
        put("ゑ", O, O, E, 'e');
        // 표기법에서 따로 정한 글자
        put("ち", J, CH, I, 'i');
        put("つ", SS, SS, EU, 'u');
        put("す", S, S, EU, 'u');
        put("ず", J, J, EU, 'u');
        put("づ", J, J, EU, 'u');
        put("ぢ", J, J, I, 'i');
        // 요음
        yoon('き', G, K, false);
        yoon('ぎ', G, G, false);
        yoon('し', S, S, false);
        yoon('じ', J, J, true);
        yoon('ぢ', J, J, true);
        yoon('ち', J, CH, true);
        yoon('に', N, N, false);
        yoon('ひ', H, H, false);
        yoon('び', B, B, false);
        yoon('ぴ', P, P, false);
        yoon('み', M, M, false);
        yoon('り', R, R, false);
        // 외래어에 쓰는 표기
        put("ふぁ", P, P, A, 'a');
        put("ふぃ", P, P, I, 'i');
        put("ふぇ", P, P, E, 'e');
        put("ふぉ", P, P, OH, 'o');
        put("てぃ", T, T, I, 'i');
        put("でぃ", D, D, I, 'i');
        put("とぅ", T, T, U, 'u');
        put("どぅ", D, D, U, 'u');
        put("うぃ", O, O, WI, 'i');
        put("うぇ", O, O, WE, 'e');
        put("うぉ", O, O, WO, 'o');
        put("しぇ", S, S, YE, 'e');
        put("じぇ", J, J, E, 'e');
        put("ちぇ", CH, CH, E, 'e');
        put("ゔぁ", B, B, A, 'a');
        put("ゔぃ", B, B, I, 'i');
        put("ゔぇ", B, B, E, 'e');
        put("ゔぉ", B, B, OH, 'o');
        put("ゔ", B, B, U, 'u');
        put("ぁ", O, O, A, 'a');
        put("ぃ", O, O, I, 'i');
        put("ぅ", O, O, U, 'u');
        put("ぇ", O, O, E, 'e');
        put("ぉ", O, O, OH, 'o');
        put("ゃ", O, O, YA, 'a');
        put("ゅ", O, O, YU, 'u');
        put("ょ", O, O, YO, 'o');
    }

    /**
     * @return 한글 표기. 가나가 아닌 일본어 글자(한자)가 섞여 있거나 옮길 내용이 없으면 null
     */
    public static String convert(String kana) {
        if (kana == null) return null;
        String text = Normalizer.normalize(kana, Normalizer.Form.NFKC).trim();
        if (text.isEmpty()) return null;

        StringBuilder out = new StringBuilder();
        boolean wordStart = true;
        char lastSeries = 0;        // 직전 음절의 모음 계열 (장모음 판단용)
        boolean converted = false;

        for (int i = 0; i < text.length(); i++) {
            char raw = text.charAt(i);
            char c = (raw >= 'ァ' && raw <= 'ヶ') ? (char) (raw - 0x60) : raw;   // 가타카나 → 히라가나

            if (c == 'ー') {                      // 장음 부호: 적지 않는다
                continue;
            }
            if (c == 'ん') {
                if (!addFinal(out, 4)) out.append('은');
                wordStart = false;
                lastSeries = 0;
                converted = true;
                continue;
            }
            if (c == 'っ') {
                addFinal(out, 19);
                lastSeries = 0;
                continue;
            }

            int[] entry = null;
            if (i + 1 < text.length()) {
                char nextRaw = text.charAt(i + 1);
                char next = (nextRaw >= 'ァ' && nextRaw <= 'ヶ') ? (char) (nextRaw - 0x60) : nextRaw;
                entry = TABLE.get("" + c + next);
                if (entry != null) i++;
            }
            if (entry == null) entry = TABLE.get(String.valueOf(c));

            if (entry == null) {
                // 가나가 아닌 글자: 한자면 변환 실패, 그 밖(숫자·영문·기호·공백)은 그대로 둔다
                if (isIdeograph(raw)) return null;
                out.append(raw == '・' || raw == '･' ? ' ' : raw);
                wordStart = true;
                lastSeries = 0;
                continue;
            }

            char series = (char) entry[3];
            boolean bareVowel = entry[0] == O && entry[2] != YA && entry[2] != YU && entry[2] != YO && entry[2] != WA
                    && entry[2] != WI && entry[2] != WE && entry[2] != WO;
            if (bareVowel && !wordStart && lastSeries != 0) {
                // 장모음: 같은 모음이 이어지거나, お단 뒤의 う
                boolean sameVowel = series == lastSeries && series != 'e';
                boolean ou = lastSeries == 'o' && series == 'u';
                if (sameVowel || ou) {
                    lastSeries = 0;       // 세 번 이어지는 모음까지 줄이지는 않는다
                    continue;
                }
            }

            int initial = wordStart ? entry[0] : entry[1];
            out.append((char) (0xAC00 + (initial * 21 + entry[2]) * 28));
            wordStart = false;
            lastSeries = series;
            converted = true;
        }
        if (!converted) return null;
        String result = out.toString().replaceAll("\\s{2,}", " ").trim();
        return result.isEmpty() ? null : result;
    }

    /** 직전 글자가 받침 없는 한글이면 받침을 붙인다. */
    private static boolean addFinal(StringBuilder out, int finalIndex) {
        if (out.length() == 0) return false;
        char last = out.charAt(out.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3 || (last - 0xAC00) % 28 != 0) return false;
        out.setCharAt(out.length() - 1, (char) (last + finalIndex));
        return true;
    }

    private static boolean isIdeograph(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || c == '々';
    }
}
