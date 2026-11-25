package com.settlementmatcher.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.settlementmatcher.config.MatcherProperties.LedgerColumns;
import com.settlementmatcher.domain.LedgerEntry;
import com.settlementmatcher.io.RowParser.Parsed;

class RowParserTest {

    static final LedgerColumns COLS = new LedgerColumns("전표번호", "매출일자", "거래처명", "합계금액");
    static final List<String> HEADER = List.of("전표번호", "매출일자", "거래처명", "합계금액");

    @Test
    void 키워드가_있는_행은_날짜를_못_읽어도_건너뛴다() {
        Parsed<LedgerEntry> p = RowParser.parseLedger(List.of(
                HEADER,
                List.of("L1", "2026-10-01", "가람", "1,000"),
                List.of("", "", "", ""),
                List.of("", "총합계", "", "1,000")), COLS, List.of("합계"));
        assertThat(p.items()).hasSize(1);
        assertThat(p.skippedRows()).containsExactly(4);
        assertThat(p.dataRows()).isEqualTo(2);
    }

    @Test
    void 키워드가_없고_날짜를_못_읽는_행이_있으면_멈춘다() {
        assertThatThrownBy(() -> RowParser.parseLedger(List.of(
                HEADER,
                List.of("L1", "날짜없음", "가람", "1,000")), COLS, List.of("합계")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2행 '매출일자' 값을 날짜로 읽을 수 없습니다");
    }

    @Test
    void 날짜를_읽을_수_있는_행은_키워드가_있어도_거래로_읽는다() {
        Parsed<LedgerEntry> p = RowParser.parseLedger(List.of(
                HEADER,
                List.of("L1", "2026-10-01", "합계상사", "1,000")), COLS, List.of("합계"));
        assertThat(p.items()).extracting(LedgerEntry::name).containsExactly("합계상사");
        assertThat(p.skippedRows()).isEmpty();
    }
}
