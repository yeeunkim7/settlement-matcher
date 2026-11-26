package com.settlementmatcher.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

/** 컨트롤러를 직접 호출해 결과 엑셀의 요약 시트를 확인한다. */
@SpringBootTest
class MatchControllerTest {

    @Autowired
    MatchController controller;

    @ParameterizedTest
    @CsvSource({
            "가상데이터_은행거래내역_2026-10.csv, 가상데이터_매출장부_2026-10.csv, 26, -",
            "가상데이터_은행거래내역_2026-10.xlsx, 가상데이터_매출장부_2026-10.xlsx, 26, -",
            "가상데이터_은행거래내역_2026-10_계좌정보포함.xlsx, 가상데이터_매출장부_2026-10.csv, 27, 31",
    })
    void 요약_시트_건수_합이_원본_데이터_행과_맞다(String bankFile, String ledgerFile, int bankDataRows,
            String bankSkipped) throws Exception {
        byte[] xlsx = controller.match(upload("bank", bankFile), upload("ledger", ledgerFile)).getBody();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            assertThat(sheetNames(wb)).containsExactly("요약", "일치", "불일치", "검토 필요", "원본");
            Map<String, List<String>> s = summary(wb.getSheet("요약"));

            assertThat(s.get("일치")).containsExactly("14", "14");
            assertThat(s.get("불일치")).containsExactly("6", "7");
            assertThat(s.get("검토 필요")).containsExactly("4", "4");
            assertThat(s.get("제외 (출금)")).containsExactly("2", "-");
            assertThat(s.get("합계")).containsExactly(String.valueOf(bankDataRows), "25");
            assertThat(s.get("원본 데이터 행 (머리글 아래, 빈 행 제외)"))
                    .containsExactly(String.valueOf(bankDataRows), "25");
            assertThat(s.get("검산 (합계 = 원본 데이터 행)")).containsExactly("맞음", "맞음");
            assertThat(s.get("건너뛴 행 번호")).containsExactly(bankSkipped, "-");
        }
    }

    @org.junit.jupiter.api.Test
    void 화면용_JSON에_요약과_항목이_담긴다() throws Exception {
        var res = controller.matchJson(upload("bank", "가상데이터_은행거래내역_2026-10.csv"),
                upload("ledger", "가상데이터_매출장부_2026-10.csv"));

        assertThat(res.summary().matched()).isEqualTo(14);
        assertThat(res.summary().unmatched()).isEqualTo(13);
        assertThat(res.summary().review()).isEqualTo(8);
        assertThat(res.summary().bank().balanced()).isTrue();
        assertThat(res.summary().ledger().balanced()).isTrue();
        assertThat(res.unmatched()).hasSize(13);
        assertThat(res.review()).hasSize(8);
        assertThat(res.matched()).hasSize(14);
        try (Workbook wb = WorkbookFactory.create(
                new ByteArrayInputStream(java.util.Base64.getDecoder().decode(res.xlsxBase64())))) {
            assertThat(sheetNames(wb)).containsExactly("요약", "일치", "불일치", "검토 필요", "원본");
        }
    }

    private static MockMultipartFile upload(String field, String file) throws Exception {
        return new MockMultipartFile(field, file, "application/octet-stream",
                Files.readAllBytes(Path.of("samples", file)));
    }

    private static List<String> sheetNames(Workbook wb) {
        return java.util.stream.IntStream.range(0, wb.getNumberOfSheets()).mapToObj(wb::getSheetName).toList();
    }

    private static Map<String, List<String>> summary(Sheet sh) {
        DataFormatter f = new DataFormatter();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Row row : sh) {
            if (row.getRowNum() == 0) continue;
            out.put(f.formatCellValue(row.getCell(0)),
                    List.of(f.formatCellValue(row.getCell(1)).replace(",", ""),
                            f.formatCellValue(row.getCell(2)).replace(",", "")));
        }
        return out;
    }
}
