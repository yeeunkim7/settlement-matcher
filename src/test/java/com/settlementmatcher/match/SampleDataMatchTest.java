package com.settlementmatcher.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.settlementmatcher.config.MatcherProperties;
import com.settlementmatcher.domain.BankTxn;
import com.settlementmatcher.domain.LedgerEntry;
import com.settlementmatcher.domain.MatchResult;
import com.settlementmatcher.domain.MatchResult.Flagged;
import com.settlementmatcher.io.RowParser;
import com.settlementmatcher.io.TableReader;

/** samples/README.md의 '케이스별 예상 결과'를 application.yml 기본 설정으로 검증한다. */
@SpringBootTest
class SampleDataMatchTest {

    @Autowired
    MatcherProperties props;
    @Autowired
    SettlementMatcher matcher;

    MatchResult result;
    List<BankTxn> bank;
    List<LedgerEntry> ledger;

    @BeforeEach
    void run() throws Exception {
        Path bankPath = Path.of("samples", "가상데이터_은행거래내역_2026-10.csv");
        Path ledgerPath = Path.of("samples", "가상데이터_매출장부_2026-10.csv");
        bank = RowParser.parseBank(TableReader.read(bankPath.toString(), Files.readAllBytes(bankPath)),
                props.bankColumns());
        ledger = RowParser.parseLedger(TableReader.read(ledgerPath.toString(), Files.readAllBytes(ledgerPath)),
                props.ledgerColumns());
        result = matcher.match(bank, ledger);
    }

    @Test
    void 모든_행이_한_곳에만_분류된다() {
        assertThat(bank).hasSize(25);
        assertThat(ledger).hasSize(24);
        assertThat(result.excludedWithdrawals()).isEqualTo(2);

        long ledgerCount = result.matched().size()
                + result.unmatched().stream().filter(f -> !f.isBank()).count()
                + result.review().stream().filter(f -> !f.isBank()).count();
        long bankCount = result.matched().size()
                + result.unmatched().stream().filter(Flagged::isBank).count()
                + result.review().stream().filter(Flagged::isBank).count()
                + result.excludedWithdrawals();
        assertThat(ledgerCount).isEqualTo(24);
        assertThat(bankCount).isEqualTo(25);
    }

    @Test
    void 일치() {
        assertThat(result.matched()).extracting(p -> p.ledger().id()).containsExactlyInAnyOrder(
                "L001", "L002", "L003", "L004", "L005", "L007", "L012", "L013", "L014", "L015", "L018", "L019",
                "L022");
    }

    @Test
    void 검토_필요() {
        assertThat(result.review()).filteredOn(f -> !f.isBank()).extracting(f -> f.ledger().id())
                .containsExactlyInAnyOrder("L009", "L010", "L023", "L024");
        assertThat(result.review()).filteredOn(Flagged::isBank).extracting(f -> f.bank().name())
                .containsExactlyInAnyOrder("두리상회", "그린푸드시", "그린푸드시", "이자");
    }

    @Test
    void 불일치와_사유() {
        Map<String, String> ledgerReasons = result.unmatched().stream().filter(f -> !f.isBank())
                .collect(Collectors.toMap(f -> f.ledger().id(), Flagged::reason));
        assertThat(ledgerReasons).containsOnlyKeys("L006", "L008", "L011", "L016", "L017", "L020", "L021");
        assertThat(ledgerReasons.get("L006")).startsWith("입금이 장부보다 3일 늦음");
        assertThat(ledgerReasons.get("L008")).startsWith("금액 차이 36,300원 (3.30%");
        assertThat(ledgerReasons.get("L011")).startsWith("거래처명이 짧아 부분 일치 제외");
        assertThat(ledgerReasons.get("L016")).startsWith("금액 차이 9,000원");
        assertThat(ledgerReasons.get("L017")).startsWith("거래처명 불일치");
        assertThat(ledgerReasons.get("L020")).isEqualTo("은행에 대응 입금 없음");

        Map<String, String> bankReasons = result.unmatched().stream().filter(Flagged::isBank)
                .collect(Collectors.toMap(f -> f.bank().name(), Flagged::reason));
        assertThat(bankReasons).containsOnlyKeys("하늘인쇄", "온누리쇼핑", "가람인테리어", "바른식품", "김별빛", "홍길동");
        assertThat(bankReasons.get("홍길동")).isEqualTo("장부에 대응 거래 없음");
    }

    @Test
    void 일치_비고에_보정_내용이_남는다() {
        Map<String, String> notes = result.matched().stream()
                .collect(Collectors.toMap(p -> p.ledger().id(), MatchResult.MatchedPair::note));
        assertThat(notes.get("L002")).isEmpty();
        assertThat(notes.get("L001")).isEqualTo("거래처명 정규화 후 일치");
        assertThat(notes.get("L004")).isEqualTo("거래처명 부분 일치");
        assertThat(notes.get("L005")).isEqualTo("입금 +1일");
        assertThat(notes.get("L007")).isEqualTo("수수료 차감 추정 500원");
        assertThat(notes.get("L022")).isEqualTo("입금 -1일");
    }
}
