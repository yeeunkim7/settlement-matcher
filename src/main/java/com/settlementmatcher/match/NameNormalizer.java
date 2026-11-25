package com.settlementmatcher.match;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 거래처명 비교용 정규화: 공백 제거 → 대문자화 → (주)·주식회사 등 표기 제거. */
public final class NameNormalizer {

    private final List<String> stripTokens;

    public NameNormalizer(List<String> stripTokens) {
        // 긴 표기부터 지워야 "(주)"가 "주식회사"의 일부를 먼저 지우는 식의 간섭이 없다.
        this.stripTokens = stripTokens.stream()
                .map(NameNormalizer::squash)
                .filter(t -> !t.isEmpty())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    public String normalize(String raw) {
        String s = squash(raw);
        for (String token : stripTokens) {
            s = s.replace(token, "");
        }
        return s;
    }

    private static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
