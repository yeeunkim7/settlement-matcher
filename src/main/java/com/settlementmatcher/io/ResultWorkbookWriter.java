package com.settlementmatcher.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.settlementmatcher.domain.BankTxn;
import com.settlementmatcher.domain.LedgerEntry;
import com.settlementmatcher.domain.MatchResult;
import com.settlementmatcher.domain.MatchResult.Flagged;
import com.settlementmatcher.domain.MatchResult.MatchedPair;

/** 대사 결과를 엑셀로 쓴다. 시트: 요약 / 일치 / 불일치 / 검토 필요 / 원본. */
public final class ResultWorkbookWriter {

    private ResultWorkbookWriter() {
    }

    /**
     * @param rows        파일에서 읽은 표 전체(원본 시트에 그대로 옮김)
     * @param dataRows    머리글 아래의 비어 있지 않은 행 수
     * @param skippedRows 키워드로 건너뛴 행 번호
     */
    public record Source(String filename, List<List<String>> rows, int dataRows, List<Integer> skippedRows) {
    }

    public static byte[] write(MatchResult result, Source bank, Source ledger) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            writeSummary(wb.createSheet("요약"), st, result, bank, ledger);
            writeMatched(wb.createSheet("일치"), st, result.matched());
            writeFlagged(wb.createSheet("불일치"), st, result.unmatched(), false);
            writeFlagged(wb.createSheet("검토 필요"), st, result.review(), true);
            writeOriginal(wb.createSheet("원본"), st, bank, ledger);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 은행·장부별 건수. 각 열에서 일치+불일치+검토 필요+제외+건너뜀 = 원본 데이터 행. */
    private static void writeSummary(Sheet sh, Styles st, MatchResult result, Source bank, Source ledger) {
        header(sh, st, new String[] {"구분", "은행 거래내역", "매출 장부"}, new int[] {34, 40, 40});
        int matched = result.matched().size();
        long bankUnmatched = result.unmatched().stream().filter(Flagged::isBank).count();
        long ledgerUnmatched = result.unmatched().size() - bankUnmatched;
        long bankReview = result.review().stream().filter(Flagged::isBank).count();
        long ledgerReview = result.review().size() - bankReview;
        int excluded = result.excludedWithdrawals();
        int bankSkipped = bank.skippedRows().size();
        int ledgerSkipped = ledger.skippedRows().size();
        long bankSum = matched + bankUnmatched + bankReview + excluded + bankSkipped;
        long ledgerSum = matched + ledgerUnmatched + ledgerReview + ledgerSkipped;

        int r = 1;
        summaryText(sh, st, r++, "파일", bank.filename(), ledger.filename());
        summaryNum(sh, st, r++, "일치", matched, matched);
        summaryNum(sh, st, r++, "불일치", bankUnmatched, ledgerUnmatched);
        summaryNum(sh, st, r++, "검토 필요", bankReview, ledgerReview);
        Row ex = sh.createRow(r++);
        text(ex, 0, "제외 (출금)", st.text);
        num(ex, 1, excluded, st.amount);
        text(ex, 2, "-", st.text);
        summaryNum(sh, st, r++, "건너뜀 (합계 등 키워드 행)", bankSkipped, ledgerSkipped);
        summaryNum(sh, st, r++, "합계", bankSum, ledgerSum);
        summaryNum(sh, st, r++, "원본 데이터 행 (머리글 아래, 빈 행 제외)", bank.dataRows(), ledger.dataRows());
        summaryText(sh, st, r++, "검산 (합계 = 원본 데이터 행)",
                bankSum == bank.dataRows() ? "맞음" : "안 맞음", ledgerSum == ledger.dataRows() ? "맞음" : "안 맞음");
        summaryText(sh, st, r++, "건너뛴 행 번호", joinRows(bank.skippedRows()), joinRows(ledger.skippedRows()));
    }

    private static void summaryNum(Sheet sh, Styles st, int r, String label, long bank, long ledger) {
        Row row = sh.createRow(r);
        text(row, 0, label, st.text);
        num(row, 1, bank, st.amount);
        num(row, 2, ledger, st.amount);
    }

    private static void summaryText(Sheet sh, Styles st, int r, String label, String bank, String ledger) {
        Row row = sh.createRow(r);
        text(row, 0, label, st.text);
        text(row, 1, bank, st.text);
        text(row, 2, ledger, st.text);
    }

    private static String joinRows(List<Integer> rows) {
        return rows.isEmpty() ? "-" : rows.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
    }

    private static void writeMatched(Sheet sh, Styles st, List<MatchedPair> pairs) {
        header(sh, st, new String[] {"장부행", "전표번호", "매출일자", "거래처명", "장부금액",
                "은행행", "거래일자", "입금자명", "입금액", "차액", "비고"},
                new int[] {8, 12, 12, 22, 14, 8, 12, 22, 14, 10, 40});
        int r = 1;
        for (MatchedPair p : pairs) {
            Row row = sh.createRow(r++);
            LedgerEntry l = p.ledger();
            BankTxn b = p.bank();
            num(row, 0, l.rowNo(), st.text);
            text(row, 1, l.id(), st.text);
            date(row, 2, l.date(), st.date);
            text(row, 3, l.name(), st.text);
            num(row, 4, l.amount(), st.amount);
            num(row, 5, b.rowNo(), st.text);
            date(row, 6, b.date(), st.date);
            text(row, 7, b.name(), st.text);
            num(row, 8, b.deposit(), st.amount);
            num(row, 9, p.amountDiff(), st.amount);
            text(row, 10, p.note(), st.text);
        }
    }

    private static void writeFlagged(Sheet sh, Styles st, List<Flagged> items, boolean withGroup) {
        String[] titles = {"그룹", "구분", "원본행", "일자", "거래처명/입금자명", "금액", "전표번호/적요",
                withGroup ? "검토 사유" : "불일치 사유"};
        int[] widths = {6, 12, 8, 12, 22, 14, 14, 70};
        int offset = withGroup ? 0 : 1;
        header(sh, st, java.util.Arrays.copyOfRange(titles, offset, titles.length),
                java.util.Arrays.copyOfRange(widths, offset, widths.length));
        int r = 1;
        for (Flagged f : items) {
            Row row = sh.createRow(r++);
            int c = 0;
            if (withGroup) num(row, c++, f.group(), st.text);
            if (f.isBank()) {
                BankTxn b = f.bank();
                text(row, c++, withGroup ? "은행" : "은행만 있음", st.text);
                num(row, c++, b.rowNo(), st.text);
                date(row, c++, b.date(), st.date);
                text(row, c++, b.name(), st.text);
                num(row, c++, b.deposit(), st.amount);
                text(row, c++, b.memo(), st.text);
            } else {
                LedgerEntry l = f.ledger();
                text(row, c++, withGroup ? "장부" : "장부만 있음", st.text);
                num(row, c++, l.rowNo(), st.text);
                date(row, c++, l.date(), st.date);
                text(row, c++, l.name(), st.text);
                num(row, c++, l.amount(), st.amount);
                text(row, c++, l.id(), st.text);
            }
            text(row, c, f.reason(), st.text);
        }
    }

    /** 원본 파일 내용을 손대지 않고 그대로 옮긴다(출금 등 매칭에서 뺀 행 포함). */
    private static void writeOriginal(Sheet sh, Styles st, Source bank, Source ledger) {
        int r = 0;
        r = writeSource(sh, st, r, "[은행 거래내역] " + bank.filename(), bank.rows());
        r = writeSource(sh, st, r + 1, "[매출 장부] " + ledger.filename(), ledger.rows());
        sh.setColumnWidth(0, 8 * 256);
        for (int c = 1; c <= 10; c++) {
            sh.setColumnWidth(c, 16 * 256);
        }
    }

    private static int writeSource(Sheet sh, Styles st, int r, String title, List<List<String>> rows) {
        text(sh.createRow(r++), 0, title, st.header);
        Row head = sh.createRow(r++);
        text(head, 0, "원본행", st.header);
        for (int i = 0; i < rows.size(); i++) {
            Row row = sh.createRow(r++);
            num(row, 0, i + 1, st.text);
            List<String> cells = rows.get(i);
            for (int c = 0; c < cells.size(); c++) {
                text(row, c + 1, cells.get(c), st.text);
            }
        }
        return r;
    }

    private static void header(Sheet sh, Styles st, String[] titles, int[] widths) {
        Row row = sh.createRow(0);
        for (int c = 0; c < titles.length; c++) {
            text(row, c, titles[c], st.header);
            sh.setColumnWidth(c, widths[c] * 256);
        }
        sh.createFreezePane(0, 1);
    }

    private static void text(Row row, int c, String v, CellStyle style) {
        Cell cell = row.createCell(c);
        cell.setCellValue(v == null ? "" : v);
        cell.setCellStyle(style);
    }

    private static void num(Row row, int c, long v, CellStyle style) {
        Cell cell = row.createCell(c);
        cell.setCellValue(v);
        cell.setCellStyle(style);
    }

    private static void date(Row row, int c, LocalDate v, CellStyle style) {
        Cell cell = row.createCell(c);
        cell.setCellValue(v);
        cell.setCellStyle(style);
    }

    private static final class Styles {
        final CellStyle header;
        final CellStyle text;
        final CellStyle amount;
        final CellStyle date;

        Styles(XSSFWorkbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            text = wb.createCellStyle();
            amount = wb.createCellStyle();
            amount.setDataFormat(wb.createDataFormat().getFormat("#,##0"));
            date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
        }
    }
}
