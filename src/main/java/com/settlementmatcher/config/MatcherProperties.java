package com.settlementmatcher.config;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml의 matcher.* 설정. 매칭 규칙과 열 이름을 코드 밖으로 뺀다. */
@ConfigurationProperties("matcher")
public record MatcherProperties(
        int dateToleranceDays,
        BigDecimal feeToleranceRate,
        int minPartialNameLength,
        List<String> nonSalesMemoKeywords,
        List<String> nameStripTokens,
        List<String> skipRowKeywords,
        BankColumns bankColumns,
        LedgerColumns ledgerColumns) {

    public MatcherProperties {
        nonSalesMemoKeywords = nonSalesMemoKeywords == null ? List.of() : List.copyOf(nonSalesMemoKeywords);
        nameStripTokens = nameStripTokens == null ? List.of() : List.copyOf(nameStripTokens);
        skipRowKeywords = skipRowKeywords == null ? List.of() : List.copyOf(skipRowKeywords);
    }

    public record BankColumns(String date, String memo, String name, String withdrawal, String deposit) {
        public List<String> all() {
            return List.of(date, memo, name, withdrawal, deposit);
        }
    }

    public record LedgerColumns(String id, String date, String name, String amount) {
        public List<String> all() {
            return List.of(id, date, name, amount);
        }
    }
}
