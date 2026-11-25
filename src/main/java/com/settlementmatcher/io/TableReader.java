package com.settlementmatcher.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * CSV / 엑셀 파일을 문자열 표(행 목록)로 읽는다. 반환 목록의 인덱스 + 1이 원본 파일의 행 번호다.
 * 엑셀은 첫 번째 시트만 읽는다.
 */
public final class TableReader {

    private static final Charset MS949 = Charset.forName("MS949");

    private TableReader() {
    }

    public static List<List<String>> read(String filename, byte[] bytes) throws IOException {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return readCsv(bytes);
        }
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return readExcel(bytes);
        }
        throw new IllegalArgumentException("지원하지 않는 파일 형식입니다: " + filename + " (.csv, .xlsx, .xls만 가능)");
    }

    static List<List<String>> readCsv(byte[] bytes) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(false).get();
        List<List<String>> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(decode(bytes), format)) {
            for (CSVRecord record : parser) {
                List<String> row = new ArrayList<>(record.size());
                record.forEach(v -> row.add(v.strip()));
                rows.add(row);
            }
        }
        return rows;
    }

    /** UTF-8(BOM 유무 무관)로 읽고, UTF-8이 아니면 국내 은행 파일에 흔한 MS949(CP949)로 읽는다. */
    static String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, MS949);
        }
    }

    static List<List<String>> readExcel(byte[] bytes) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.KOREA);
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        cells.add(cellText(row.getCell(c), formatter));
                    }
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case STRING -> cell.getStringCellValue().strip();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA, BLANK, ERROR, _NONE -> formatter.formatCellValue(cell).strip();
        };
    }
}
