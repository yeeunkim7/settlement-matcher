package com.settlementmatcher.io;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.settlementmatcher.config.MatcherProperties.BankColumns;
import com.settlementmatcher.config.MatcherProperties.LedgerColumns;
import com.settlementmatcher.domain.BankTxn;
import com.settlementmatcher.domain.LedgerEntry;

/**
 * 문자열 표를 은행 거래 / 장부 행으로 바꾼다. 열 이름은 설정에서 받는다.
 * 파일 위쪽에 제목 행이 있어도 되도록, 필요한 열 이름이 모두 있는 첫 행을 머리글로 본다.
 */
public final class RowParser {

    private static final int HEADER_SEARCH_ROWS = 30;
    private static final Pattern DATE = Pattern.compile("(\\d{4})\\D{0,2}(\\d{1,2})\\D{0,2}(\\d{1,2})");

    private RowParser() {
    }

    public static List<BankTxn> parseBank(List<List<String>> rows, BankColumns cols) {
        Header h = findHeader(rows, cols.all(), "은행 거래내역", "matcher.bank-columns");
        List<BankTxn> out = new ArrayList<>();
        for (int r = h.row + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (isBlank(row)) continue;
            int rowNo = r + 1;
            out.add(new BankTxn(rowNo,
                    date(h.get(row, cols.date()), rowNo, cols.date()),
                    h.get(row, cols.memo()),
                    h.get(row, cols.name()),
                    amount(h.get(row, cols.withdrawal()), rowNo, cols.withdrawal()),
                    amount(h.get(row, cols.deposit()), rowNo, cols.deposit())));
        }
        return out;
    }

    public static List<LedgerEntry> parseLedger(List<List<String>> rows, LedgerColumns cols) {
        Header h = findHeader(rows, cols.all(), "매출 장부", "matcher.ledger-columns");
        List<LedgerEntry> out = new ArrayList<>();
        for (int r = h.row + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (isBlank(row)) continue;
            int rowNo = r + 1;
            out.add(new LedgerEntry(rowNo,
                    h.get(row, cols.id()),
                    date(h.get(row, cols.date()), rowNo, cols.date()),
                    h.get(row, cols.name()),
                    amount(h.get(row, cols.amount()), rowNo, cols.amount())));
        }
        return out;
    }

    private record Header(int row, Map<String, Integer> index) {
        String get(List<String> row, String column) {
            int i = index.get(column);
            return i < row.size() ? row.get(i) : "";
        }
    }

    private static Header findHeader(List<List<String>> rows, List<String> required, String what, String configKey) {
        for (int r = 0; r < Math.min(rows.size(), HEADER_SEARCH_ROWS); r++) {
            Map<String, Integer> index = new HashMap<>();
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                index.putIfAbsent(row.get(c), c);
            }
            if (index.keySet().containsAll(required)) {
                return new Header(r, index);
            }
        }
        throw new IllegalArgumentException(what + " 파일에서 필요한 열을 찾지 못했습니다. 필요한 열: " + required
                + ". 열 이름이 다르면 설정 파일의 " + configKey + "를 바꿔 주세요.");
    }

    private static boolean isBlank(List<String> row) {
        return row.stream().allMatch(String::isBlank);
    }

    static LocalDate date(String raw, int rowNo, String column) {
        Matcher m = DATE.matcher(raw);
        if (m.find()) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)));
            } catch (DateTimeException ignored) {
                // 아래에서 같은 오류로 처리
            }
        }
        throw new IllegalArgumentException(rowNo + "행 '" + column + "' 값을 날짜로 읽을 수 없습니다: '" + raw + "'");
    }

    static long amount(String raw, int rowNo, String column) {
        String t = raw.replaceAll("[,\\s원₩]", "");
        if (t.isEmpty() || t.equals("-")) {
            return 0;
        }
        try {
            return new BigDecimal(t).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(rowNo + "행 '" + column + "' 값을 금액으로 읽을 수 없습니다: '" + raw + "'");
        }
    }
}
