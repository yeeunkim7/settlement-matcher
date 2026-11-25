# settlement-matcher

은행 거래내역과 매출 장부(CSV/엑셀)를 대사해 **일치 / 불일치 / 검토 필요 / 원본** 4개 시트로 된 결과 엑셀을 만드는 로컬 웹앱입니다.
고객 PC에서만 실행되고(127.0.0.1), 업로드한 파일은 디스크에 저장하지 않습니다.

## 실행

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.13.10002-hotspot"
.\mvnw.cmd package
java -jar target\settlement-matcher-0.0.1-SNAPSHOT.jar
```

브라우저에서 http://127.0.0.1:8080 에 접속해 두 파일을 올리면 결과 엑셀이 다운로드됩니다.
데모용 가상 데이터는 `samples/`에 있습니다.

## 설정 (`src/main/resources/application.yml`)

| 키 | 기본값 | 의미 |
|---|---|---|
| `matcher.date-tolerance-days` | 1 | 날짜 허용 범위 ±일 |
| `matcher.fee-tolerance-rate` | 0.005 | 입금이 적을 때 허용하는 차이 비율 (0.5%) |
| `matcher.min-partial-name-length` | 3 | 부분 일치를 인정하는 최소 글자 수 |
| `matcher.non-sales-memo-keywords` | 이자 | 적요에 있으면 비매출로 보고 '검토 필요'로 보냄 |
| `matcher.name-strip-tokens` | (주), ㈜, 주식회사 … | 거래처명 비교 전에 지우는 표기 |
| `matcher.bank-columns.*`, `matcher.ledger-columns.*` | | 파일별 열 이름 |

jar를 실행하는 폴더에 `application.yml`을 두면, 다시 빌드하지 않아도 그 파일의 값이 우선 적용됩니다(Spring Boot 외부 설정).
규칙을 정한 근거는 `DECISION_LOG.md`에 있습니다.

## 핵심 코드 경로

- `match/SettlementMatcher.java`: 후보 연결 → 묶음(Union-Find) 크기로 일치/검토 필요를 판정하고, 불일치 사유를 생성
- `match/NameNormalizer.java`: 거래처명 정규화
- `io/TableReader.java`: CSV(UTF-8/CP949 자동 판별)와 엑셀 읽기 / `io/RowParser.java`: 머리글 행 탐색, 날짜·금액 파싱
- `io/ResultWorkbookWriter.java`: 결과 엑셀 생성
- `web/MatchController.java`: 업로드 → 매칭 → 다운로드
- `src/test/.../SampleDataMatchTest.java`: `samples/README.md`의 예상 결과 검증
