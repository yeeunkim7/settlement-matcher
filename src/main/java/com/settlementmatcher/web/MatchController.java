package com.settlementmatcher.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

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
import com.settlementmatcher.io.ResultWorkbookWriter;
import com.settlementmatcher.io.ResultWorkbookWriter.Source;
import com.settlementmatcher.io.RowParser;
import com.settlementmatcher.io.TableReader;
import com.settlementmatcher.match.SettlementMatcher;

/** 업로드 → 매칭 → 결과 엑셀 다운로드. 업로드 파일은 저장하지 않고 요청 처리 중 메모리에서만 쓴다. */
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
        List<List<String>> bankRows = TableReader.read(bankFile.getOriginalFilename(), bankFile.getBytes());
        List<List<String>> ledgerRows = TableReader.read(ledgerFile.getOriginalFilename(), ledgerFile.getBytes());

        List<BankTxn> bank = RowParser.parseBank(bankRows, props.bankColumns());
        List<LedgerEntry> ledger = RowParser.parseLedger(ledgerRows, props.ledgerColumns());
        MatchResult result = matcher.match(bank, ledger);

        byte[] xlsx = ResultWorkbookWriter.write(result,
                new Source(bankFile.getOriginalFilename(), bankRows),
                new Source(ledgerFile.getOriginalFilename(), ledgerRows));

        String name = "대사결과_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .body(xlsx);
    }

    @ExceptionHandler({IllegalArgumentException.class, IOException.class})
    public ResponseEntity<String> badInput(Exception e) {
        String html = "<!doctype html><meta charset=\"utf-8\"><title>처리 실패</title>"
                + "<body style=\"font-family:sans-serif;max-width:640px;margin:48px auto;line-height:1.6\">"
                + "<h2>파일을 처리하지 못했습니다</h2><p>" + HtmlUtils.htmlEscape(String.valueOf(e.getMessage()))
                + "</p><p><a href=\"/\">돌아가기</a></p></body>";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(html);
    }
}
