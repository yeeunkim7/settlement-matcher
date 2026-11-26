# settlement-matcher

은행 거래내역과 매출 장부(CSV/엑셀)를 대사해 **요약 / 일치 / 불일치 / 검토 필요 / 원본** 5개 시트로 된 결과 엑셀을 만드는 로컬 웹앱입니다.
고객 PC에서만 실행되고(127.0.0.1), 업로드한 파일은 디스크에 저장하지 않습니다.

## 실행

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.13.10002-hotspot"
.\mvnw.cmd package
java -jar target\settlement-matcher-0.0.1-SNAPSHOT.jar
```

브라우저에서 http://127.0.0.1:8080 에 접속해 두 파일을 올리면 화면에 결과가 나오고, 결과 엑셀을 받을 수 있습니다.
데모용 가상 데이터는 `samples/`에 있습니다.

## 설정 (`src/main/resources/application.yml`)

| 키 | 기본값 | 의미 |
|---|---|---|
| `matcher.date-tolerance-days` | 1 | 날짜 허용 범위 ±일 |
| `matcher.fee-tolerance-rate` | 0.005 | 입금이 적을 때 허용하는 차이 비율 (0.5%) |
| `matcher.min-partial-name-length` | 3 | 부분 일치를 인정하는 최소 글자 수 |
| `matcher.non-sales-memo-keywords` | 이자 | 적요에 있으면 비매출로 보고 '검토 필요'로 보냄 |
| `matcher.name-strip-tokens` | (주), ㈜, 주식회사 … | 거래처명 비교 전에 지우는 표기 |
| `matcher.skip-row-keywords` | 합계 | 날짜를 읽을 수 없는 행 중 이 단어가 있으면 건너뜀 (그 외에는 처리를 멈춤) |
| `matcher.bank-columns.*`, `matcher.ledger-columns.*` | | 파일별 열 이름 |
| `app.open-browser` | false | 준비가 끝나면 기본 브라우저로 접속 주소를 연다 (납품용 설정에서는 true) |

jar를 실행하는 폴더에 `application.yml`을 두면, 다시 빌드하지 않아도 그 파일의 값이 우선 적용됩니다(Spring Boot 외부 설정).
규칙을 정한 근거는 `DECISION_LOG.md`에 있습니다.

## 납품 파일 만들기

```powershell
powershell -ExecutionPolicy Bypass -File scripts\package.ps1                # 테스트 포함 빌드
powershell -ExecutionPolicy Bypass -File scripts\package.ps1 -SkipTests     # 테스트 생략
powershell -ExecutionPolicy Bypass -File scripts\package.ps1 -JdkHome "D:\jdk-21"   # JDK 위치 지정
```

결과는 `dist\settlement-matcher-<버전>.zip`입니다(약 72MB, `dist/`는 git에서 제외). 필요한 것은 **`jmods` 폴더가 있는 JDK 21**뿐이고(Temurin JDK에는 들어 있음), 인터넷 연결은 필요 없습니다. JDK 위치는 `-JdkHome` → `JAVA_HOME` → 기본 설치 경로 순으로 찾습니다.

```
settlement-matcher-0.0.1├─ 실행.bat                  더블클릭하면 실행 (packaging/files/)
├─ 사용안내.txt              고객용 안내 (packaging/files/, UTF-8 BOM · CRLF)
├─ settlement-matcher.jar
├─ application.yml           src/main/resources/application.yml + packaging/application.append.yml
└─ runtime\                  jlink로 만든 Java 21 실행 환경 (압축 해제 시 약 52MB)
```

스크립트가 하는 일: `mvnw package` → `jlink`로 runtime 생성 → 위 구성으로 복사 → zip(파일 이름은 UTF-8 플래그로 저장해 한글 이름이 탐색기에서 깨지지 않음).

### 실행 환경(runtime)에 넣는 모듈

- `jdeps`로 jar 안의 클래스와 `BOOT-INF/lib/*.jar`를 하나씩 분석한 결과(모듈 jar끼리 의존을 풀다 실패하므로 jar별로 실행해 합침):
  `java.base java.compiler java.desktop java.instrument java.logging java.management java.naming java.net.http java.prefs java.scripting java.security.jgss java.sql java.xml java.xml.crypto jdk.jfr jdk.unsupported`
- 실행 중에만 불러와 `jdeps`가 못 보는 것을 직접 추가: `jdk.charsets`(EUC-KR 등 추가 문자셋), `jdk.localedata`(한국어 로캘, `--include-locales=en,ko`로 줄임), `jdk.crypto.ec`
- 참고: CSV를 읽을 때 쓰는 MS949(CP949)는 Windows용 JDK에서는 `java.base`에 들어 있어 `jdk.charsets` 없이도 읽힙니다(확인함). `jdk.charsets`는 EUC-KR 같은 다른 한글 인코딩을 쓰게 될 때를 대비해 넣어 둡니다.
- 의존성을 바꾼 뒤에는 위 분석을 다시 해서 `scripts/package.ps1`의 `$modules`를 고치고, 아래 확인을 다시 합니다.

### 실행.bat 동작

- 자기 폴더로 이동(`cd /d "%~dp0"`)한 뒤 `runtime\bin\java.exe`를 **경로로 직접** 실행합니다. `JAVA_HOME`·`PATH`·시스템 Java는 보지 않고, 어떤 java.exe든 읽어 들이는 `JAVA_TOOL_OPTIONS`·`JDK_JAVA_OPTIONS`·`_JAVA_OPTIONS`도 이 창에서만 비웁니다.
- 작업 폴더가 압축을 푼 폴더이므로 Spring Boot가 옆의 `application.yml`을 읽고, jar 안의 설정보다 우선합니다. 고객마다 이 파일만 바꾸면 됩니다.
- 브라우저는 앱이 준비를 마친 뒤(`ApplicationReadyEvent`) 실제 포트로 엽니다(`launch/Launcher.java`, `app.open-browser`). bat에서 열면 서버보다 먼저 열리거나 포트를 바꿨을 때 어긋나기 때문입니다.
- 검은 창을 닫으면 종료됩니다. 실패로 끝나면 창을 닫지 않고 멈춰 메시지를 읽을 수 있게 합니다.
- bat 파일은 ASCII로만 씁니다(cmd가 bat을 시스템 코드 페이지로 읽음). 한글 안내는 Java가 출력합니다.

### 8080 포트가 이미 쓰일 때

원래 동작(바꾸기 전): Spring Boot가 `APPLICATION FAILED TO START … Port 8080 was already in use`라는 영어 메시지를 남기고 코드 1로 끝났습니다. 실행.bat에서는 창이 바로 닫혀 고객이 이유를 볼 수 없었습니다.

지금은 설정을 읽은 직후, 웹 서버를 띄우기 전에 포트를 먼저 잡아 봅니다(`Launcher.exitIfPortBusy`).

| 상황 | 동작 |
|---|---|
| 정산 대사가 이미 떠 있음 (실행.bat을 두 번 누름) | "이미 실행 중이에요" 안내, 브라우저만 열고 코드 0으로 종료 |
| 다른 프로그램이 127.0.0.1:8080을 씀 | "8080 포트를 다른 프로그램이 쓰고 있어요 … server.port를 8081처럼 바꾸세요" 안내, 코드 1, 창 유지 |
| 다른 프로그램이 0.0.0.0:8080(모든 주소)을 씀 | Windows가 더 구체적인 127.0.0.1:8080 바인딩을 허용해 **정상 시작**, 127.0.0.1:8080 요청은 정산 대사가 받음 |

이미 떠 있는지는 그 포트의 첫 화면에 `<meta name="application-name" content="settlement-matcher">`가 있는지로 가립니다. 확인과 실제 시작 사이에 포트를 뺏기는 드문 경우에는 `main()`이 `PortInUseException`을 잡아 같은 안내를 보여 줍니다(이때는 Spring의 영어 메시지도 함께 나옴).

### 납품 전 확인 (2026-11-26, 0.0.1에서 한 것)

zip을 탐색기 방식(Shell.Application)으로 **한글과 공백이 있는 폴더**에 풀고, `JAVA_HOME=C:\no-such-java`, `PATH=C:\Windows\System32;C:\Windows`(`where java` → 없음), `JAVA_TOOL_OPTIONS=-Xmx1k`, `JDK_JAVA_OPTIONS=--no-such-option`으로 만든 뒤 실행.bat을 실행했습니다.

- 실행 중인 프로세스의 `java.exe`와 불러온 `jvm.dll`이 모두 압축을 푼 폴더의 `runtime\` 아래였고, 로그에 `Picked up …`(환경 변수 옵션을 읽었다는 표시)이 없었습니다.
- 그 상태로 샘플 3세트(CSV, xlsx, 계좌정보 포함 xlsx)와 CP949로 바꾼 은행 CSV를 올려 모두 일치 14 · 불일치 13 · 검토 필요 8, 검산 맞음을 확인했습니다.
- 위 포트 표의 세 경우와, 8080이 막힌 상태에서 바깥 `application.yml`의 `server.port`만 8081로 바꿔 8081로 뜨는 것까지 확인했습니다.

한계: 개발 PC에는 Java가 설치되어 있어 "Java가 전혀 없는 PC"를 완전히 재현하지는 못했습니다. 처음 납품할 때는 Java가 없는 PC나 Windows 샌드박스에서 한 번 실행해 보는 것을 권합니다.

## 핵심 코드 경로

- `match/SettlementMatcher.java`: 후보 연결 → 묶음(Union-Find) 크기로 일치/검토 필요를 판정하고, 불일치 사유를 생성
- `match/NameNormalizer.java`: 거래처명 정규화
- `io/TableReader.java`: CSV(UTF-8/CP949 자동 판별)와 엑셀 읽기 / `io/RowParser.java`: 머리글 행 탐색, 날짜·금액 파싱
- `io/ResultWorkbookWriter.java`: 결과 엑셀 생성 (요약 시트에 은행·장부별 건수와 검산)
- `web/MatchController.java`: 업로드 → 매칭 → 다운로드(`/match`), 화면용 JSON(`/api/match`)
- `launch/Launcher.java`: 접속 주소 안내, 브라우저 열기, 포트 충돌 안내 / `scripts/package.ps1`: 납품 zip 만들기
- `src/test/.../SampleDataMatchTest.java`: `samples/README.md`의 예상 결과 검증 (CSV·xlsx·계좌정보포함 xlsx)
- `src/test/.../MatchControllerTest.java`: 결과 엑셀의 요약 시트 건수와 검산 확인
- `src/test/.../LauncherTest.java`: 포트가 쓰일 때 이 앱이면 0, 다른 프로그램이면 1
