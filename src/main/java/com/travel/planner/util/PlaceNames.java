package com.travel.planner.util;

import com.travel.planner.entity.Place;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 장소의 "보여 줄 한국어 이름"을 다루는 도구.
 *
 * 구글에서 받은 장소 이름에는 영어·일본어로만 된 것이 섞여 있다(구글에 한국어 이름이 등록되지 않은 곳).
 * DB 의 원래 이름(place.name)은 그대로 두고, 화면·문서에 보여 줄 한국어 이름만 따로(place.name_ko) 둔다.
 */
public final class PlaceNames {

    public static final int MAX_LENGTH = 100;

    private PlaceNames() {}

    /** 한국어 이름을 따로 만들어야 하는 이름인가: 한글이 전혀 없거나, 일본어 글자(가나·한자)가 섞여 있다 */
    public static boolean needsKorean(String name) {
        if (name == null || name.isBlank()) return false;
        if (!hasLetter(name)) return false;              // 숫자·기호뿐인 이름은 옮길 것이 없다
        return !hasHangul(name) || hasJapanese(name);
    }

    public static boolean hasHangul(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) return true;
        }
        return false;
    }

    /** 히라가나·가타카나·한자(반각 가타카나, 々 포함)가 들어 있는가 */
    public static boolean hasJapanese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                    || (c >= 0xFF66 && c <= 0xFF9F) || c == 0x3005) return true;
        }
        return false;
    }

    private static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) return true;
        }
        return false;
    }

    /**
     * AI 가 돌려준 한국어 이름을 다듬는다. 쓸 수 없으면 null.
     *  - 한글이 없거나, 일본어 글자가 남아 있거나, 너무 길거나, 여러 줄이면 쓰지 않는다.
     *  - "기요미즈데라(清水寺)"처럼 원문을 괄호로 덧붙인 것은 괄호 부분만 떼어 낸다.
     */
    public static String clean(String raw, String original) {
        if (raw == null) return null;
        String s = raw.replace(' ', ' ').trim();
        if (s.contains("\n") || s.contains("|")) return null;
        s = stripQuotes(s);
        s = dropJapaneseParentheses(s);
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty() || s.length() > MAX_LENGTH) return null;
        if (!hasHangul(s) || hasJapanese(s)) return null;
        if (original != null && s.equals(original.trim())) return null;
        return s;
    }

    private static String stripQuotes(String s) {
        String out = s;
        while (out.length() >= 2) {
            char first = out.charAt(0);
            char last = out.charAt(out.length() - 1);
            boolean quoted = (first == '"' && last == '"') || (first == '\'' && last == '\'')
                    || (first == '“' && last == '”') || (first == '「' && last == '」');
            if (!quoted) break;
            out = out.substring(1, out.length() - 1).trim();
        }
        return out;
    }

    /** 일본어 글자가 든 괄호 묶음 "(…)" "（…）" 을 지운다 */
    private static String dropJapaneseParentheses(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '(' || c == '（') {
                int close = -1;
                for (int j = i + 1; j < s.length(); j++) {
                    char d = s.charAt(j);
                    if (d == ')' || d == '）') {
                        close = j;
                        break;
                    }
                }
                if (close > i && hasJapanese(s.substring(i + 1, close))) {
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 이미 만들어진 글(경고 문장, 예전에 저장한 여행 요약 등)에 들어 있는 원래 이름을 보여 줄 이름으로 바꾼다.
     * 긴 이름부터 바꿔서, 다른 이름의 일부인 짧은 이름이 먼저 바뀌는 일을 막는다. 두 글자 이하의 이름은 건드리지 않는다.
     */
    public static String localize(String text, Collection<Place> places) {
        if (text == null || text.isEmpty() || places == null || places.isEmpty()) return text;
        Map<String, String> pairs = new LinkedHashMap<>();
        for (Place p : places) {
            if (p == null || p.getName() == null) continue;
            String original = p.getName().trim();
            String shown = p.shownName();
            if (original.length() < 3 || shown == null || shown.equals(original)) continue;
            pairs.putIfAbsent(original, shown);
        }
        if (pairs.isEmpty()) return text;
        List<String> originals = new ArrayList<>(pairs.keySet());
        originals.sort((a, b) -> Integer.compare(b.length(), a.length()));
        String out = text;
        for (String original : originals) {
            if (out.contains(original)) out = out.replace(original, pairs.get(original));
        }
        return out;
    }
}
