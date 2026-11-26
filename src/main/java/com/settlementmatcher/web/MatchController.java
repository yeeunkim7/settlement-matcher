package com.settlementmatcher.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import com.settlementmatcher.config.MatcherProperties;
import com.settlementmatcher.domain.BankTxn;
import com.settlementmatcher.domain.LedgerEntry;
import com.settlementmatcher.domain.MatchResult;
import com.settlementmatcher.domain.MatchResult.Flagged;
import com.settlementmatcher.domain.MatchResult.MatchedPair;
import com.settlementmatcher.io.ResultWorkbookWriter;
import com.settlementmatcher.io.ResultWorkbookWriter.Source;
import com.settlementmatcher.io.RowParser;
import com.settlementmatcher.io.RowParser.Parsed;
import com.settlementmatcher.io.TableReader;
import com.settlementmatcher.match.SettlementMatcher;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 업로드 → 매칭 → 결과 엑셀. 업로드 파일은 저장하지 않고 요청 처리 중 메모리에서만 쓴다.
 * <ul>
 * <li>{@code POST /match}: 결과 엑셀을 바로 내려준다(자바스크립트 없이 폼으로 쓸 때).</li>
 * <li>{@code POST /api/match}: 화면에 보여줄 요약·항목과 결과 엑셀(base64)을 JSON으로 내려준다.</li>
 * </ul>
 */
@RestController
public class MatchController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final MatcherProperties props;
    private final SettlementMatcher matcher;

    public MatchController(MatcherProperties props, SettlementMatcher matcher) {
        this.props = props;
        this.matcher = matcher;
    }

    @PostMapping("/match")
    public ResponseEntity<byte[]> match(@RequestParam("bank") MultipartFile bankFile,
            @RequestParam("ledger") MultipartFile ledgerFile) throws IOException {
        Run run = run(bankFile, ledgerFile);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(run.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(run.xlsx());
    }

    @PostMapping(value = "/api/match", produces = MediaType.APPLICATION_JSON_VALUE)
    public MatchResponse matchJson(@RequestParam("bank") MultipartFile bankFile,
            @RequestParam("ledger") MultipartFile ledgerFile) throws IOException {
        Run run = run(bankFile, ledgerFile);
        MatchResult r = run.result();

        int matched = r.matched().size();
        long bankUnmatched = r.unmatched().stream().filter(Flagged::isBank).count();
        long bankReview = r.review().stream().filter(Flagged::isBank).count();
        long bankSum = matched + bankUnmatched + bankReview + r.excludedWithdrawals() + run.bank().skippedRows().size();
        long ledgerSum = matched + (r.unmatched().size() - bankUnmatched) + (r.review().size() - bankReview)
                + run.ledger().skippedRows().size();

        Summary summary = new Summary(
                matched,
                r.matched().stream().mapToLong(p -> p.ledger().amount()).sum(),
                r.unmatched().size(),
                r.review().size(),
                r.excludedWithdrawals(),
                new Side(bankFile.getOriginalFilename(), run.bank().dataRows(), bankUnmatched, bankReview,
                        run.bank().skippedRows(), bankSum == run.bank().dataRows()),
                new Side(ledgerFile.getOriginalFilename(), run.ledger().dataRows(),
                        r.unmatched().size() - bankUnmatched, r.review().size() - bankReview,
                        run.ledger().skippedRows(), ledgerSum == run.ledger().dataRows()));

        return new MatchResponse(run.fileName(), Base64.getEncoder().encodeToString(run.xlsx()), summary,
                r.unmatched().stream().map(MatchController::item).toList(),
                r.review().stream().map(MatchController::item).toList(),
                r.matched().stream().map(MatchController::pair).toList());
    }

    private Run run(MultipartFile bankFile, MultipartFile ledgerFile) throws IOException {
        List<List<String>> bankRows = TableReader.read(bankFile.getOriginalFilename(), bankFile.getBytes());
        List<List<String>> ledgerRows = TableReader.read(ledgerFile.getOriginalFilename(), ledgerFile.getBytes());

        Parsed<BankTxn> bank = RowParser.parseBank(bankRows, props.bankColumns(), props.skipRowKeywords());
        Parsed<LedgerEntry> ledger = RowParser.parseLedger(ledgerRows, props.ledgerColumns(), props.skipRowKeywords());
        MatchResult result = matcher.match(bank.items(), ledger.items());

        byte[] xlsx = ResultWorkbookWriter.write(result,
                new Source(bankFile.getOriginalFilename(), bankRows, bank.dataRows(), bank.skippedRows()),
                new Source(ledgerFile.getOriginalFilename(), ledgerRows, ledger.dataRows(), ledger.skippedRows()));

        String name = "대사결과_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
        return new Run(result, bank, ledger, xlsx, name);
    }

    private static Item item(Flagged f) {
        if (f.isBank()) {
            BankTxn b = f.bank();
            return new Item(f.group(), "bank", b.rowNo(), iso(b.date()), b.name(), b.deposit() > 0 ? b.deposit() : -b.withdrawal(),
                    b.memo(), null, f.reason());
        }
        LedgerEntry l = f.ledger();
        return new Item(f.group(), "ledger", l.rowNo(), iso(l.date()), l.name(), l.amount(), null, l.id(), f.reason());
    }

    private static Pair pair(MatchedPair p) {
        return new Pair(iso(p.bank().date()), p.bank().name(), p.bank().deposit(),
                iso(p.ledger().date()), p.ledger().name(), p.ledger().amount(), p.ledger().id(),
                p.dayDiff(), p.amountDiff(), p.note());
    }

    private static String iso(LocalDate d) {
        return d == null ? null : d.toString();
    }

    @ExceptionHandler({IllegalArgumentException.class, IOException.class})
    public ResponseEntity<?> badInput(Exception e, HttpServletRequest request) {
        String message = String.valueOf(e.getMessage());
        if (request.getRequestURI().startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("message", message));
        }
        String html = "<!doctype html><meta charset=\"utf-8\"><title>처리 실패</title>"
                + "<body style=\"font-family:sans-serif;max-width:640px;margin:48px auto;line-height:1.6\">"
                + "<h2>파일을 처리하지 못했습니다</h2><p>" + HtmlUtils.htmlEscape(message)
                + "</p><p><a href=\"/\">돌아가기</a></p></body>";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(html);
    }

    private record Run(MatchResult result, Parsed<BankTxn> bank, Parsed<LedgerEntry> ledger, byte[] xlsx,
            String fileName) {
    }

    public record MatchResponse(String fileName, String xlsxBase64, Summary summary, List<Item> unmatched,
            List<Item> review, List<Pair> matched) {
    }

    public record Summary(int matched, long matchedAmount, int unmatched, int review, int excludedWithdrawals,
            Side bank, Side ledger) {
    }

    public record Side(String fileName, int dataRows, long unmatched, long review, List<Integer> skippedRows,
            boolean balanced) {
    }

    /** side는 "bank" 또는 "ledger". 은행 출금은 amount가 음수. */
    public record Item(int group, String side, int rowNo, String date, String name, long amount, String memo,
            String id, String reason) {
    }

    public record Pair(String bankDate, String bankName, long bankAmount, String ledgerDate, String ledgerName,
            long ledgerAmount, String ledgerId, long dayDiff, long amountDiff, String note) {
    }
}
