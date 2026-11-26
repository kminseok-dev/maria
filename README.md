# MARIA — RIA 국내시장복귀계좌 관리 시스템

> 신한 DS SW Academy 팀 프로젝트 · 2026.07 ~ 2026.11 · 1차 5인 / 2차 4인
> 원본 레포: [returns-7/maria](https://github.com/returns-7/maria) · 이 README는 개인 fork에서 내 담당 기준으로 정리한 문서다.

RIA(국내시장복귀계좌)는 기준일에 보유한 해외주식을 이 계좌에서 팔아 국내 자산에 투자하면 양도소득세를 감면해 주는 한시 계좌다. MARIA는 증권사 관점의 RIA 백오피스로, 계좌 개설 심사·입고·매도·정산·인출·세액 계산·증빙서류 발급을 다룬다.

## 기술 스택

| 분류 | 기술 |
|---|---|
| Language / Framework | Java 17, Spring Boot 3.5 |
| Persistence | MyBatis (기존 도메인), Spring Data JPA (2차 신규 도메인) |
| DB / Cache | MariaDB, Redis |
| Batch | Spring Batch |
| Auth | Spring Security + JWT (HttpOnly 쿠키), 관리자 RBAC |
| View | Thymeleaf (관리자 화면), React (고객 화면, 별도 레포) |
| AI | Spring AI (OpenAI) |
| PDF | openhtmltopdf |
| CI/CD | GitHub Actions → ghcr.io → 배포 서버 ([maria-deploy](https://github.com/kminseok-dev/maria-deploy)) |

## 시스템 구성

```
              ┌─ maria (이 레포) ──────────────┐
 관리자/고객 ─▶│ RIA 계좌·매도·정산·세액·증빙   │── API ──▶ mydata (타사 RIA·순매수 조회, PostgreSQL)
              │ MariaDB · Redis                │── API ──▶ return-securities (일반계좌·국내 거래)
              └────────────────────────────────┘── API ──▶ risk (종목 위험등급, Python)
```

세 시스템은 DB를 공유하지 않고 **API로만** 연동한다. 다른 시스템의 ID는 저장하되 FK는 걸지 않고, 확정 증거로 남아야 하는 값은 스냅샷으로 함께 저장한다.

## 내 담당

| 영역 | 내용 | 현재 코드 중 내 비중 (`git blame`) |
|---|---|---|
| **세액 계산** (`domain/tax`) | 5단계 감면 공식 엔진, 세액 스냅샷 배치, 예상 감면세액, 고객 세액 브리핑 API | 84% |
| **증빙서류** (`domain/document`) | 과세특례 증빙서·순매수 내역서 발급, PDF 렌더링 | 100% |
| **커뮤니티 · 끼루 AI** (`domain/community`) | 종목 토론 게시판, 신고·관리자 처리, AI 답글(끼루) | 97% |
| 계좌 · 고객 · 매도 일부 | 고객 신청 처리 연결, 고객 인증 일부 | 33~42% |

### 1. 세액 계산 엔진 (`tax/service/TaxCalculator.java`)

감면율을 세액에 바로 곱하는 방식이 아니라 아래 5단계로 계산한다.

```
[1] 조정전공제액 = Σ(구간별 양도소득 × 구간 가중치)   가중치: 1~5월 100% · 6~7월 80% · 8~12월 50%
    가중매도금액 = Σ(구간별 매도금액 × 구간 가중치)
[2] RIA 외 계좌 순매수금액 = Σ(구간별 매수 × 가중치) − Σ(구간별 매도 × 가중치)
[3] 조정비율 = 1 − 순매수금액 / 가중매도금액        (0 이하면 공제 0)
[4] 최종공제액 = 조정전공제액 × 조정비율
[5] 최종세액 = (비가중 연간 양도소득 − 기본공제 − 최종공제액) × 세율
```

- 1~4단계는 가중 금액, 5단계는 **가중치 없는 실제 양도소득** 기준이다. 이 구분을 놓치면 세액이 틀린다.
- 금액은 `BigDecimal` + 명시적 `RoundingMode`로 계산한다.
- 세율·기본공제 등은 코드 상수가 아니라 `tax_rule` 테이블에서 조회한다. 규칙이 바뀌면 UPDATE 대신 `valid_from`/`valid_to`를 가진 새 행을 넣는다(effective-dated rule).
- 날짜 판단은 `LocalDate.now()`가 아니라 주입한 업무시계로 한다. 시연 중 관리자가 업무일시를 옮겨도 계산이 일관된다.
- 테스트: `TaxCalculatorTest`, `TaxCalculationServiceImplTest`, `TaxExpectedSnapshotTransactionTest`

### 2. 세액 스냅샷 배치 (`tax/batch/`)

```
TaxSnapshotSchedule ─▶ TaxSnapshotJobLauncher (runId 발급)
   └─ chunk Step: TargetReader ─▶ Processor(TaxCalculator) ─▶ Writer
        └─ faultTolerant + TaxSnapshotSkipPolicy + SkipListener
```

- 미리보기 계산은 저장하지 않고, **확정 근거만 스냅샷**으로 남긴다.
- 실행마다 `runId`를 발급해 이력을 추적한다.
- `TaxSnapshotSkipPolicy`는 `TAX_*` 업무 오류만 상한 안에서 skip한다. 그 외 예외는 skip하지 않고 Step을 실패시킨다. 고객 한 명의 데이터 문제로 전체 배치가 멈추지 않으면서도 시스템 오류는 숨기지 않기 위해서다.

### 3. 증빙서류 (`domain/document`)

- 확정신고 첨부용 **과세특례 증빙서**·**순매수 내역서**를 세액 계산 결과(`TaxMapper`, `TaxCalculationService`)로 만들어 PDF로 렌더링한다(`DocumentPdfRenderer`, openhtmltopdf).
- 관리자 발급 API와 고객 조회·다운로드 API를 분리했다(`AdminDocumentApi`, `CustomerDocumentApi`).

### 4. 커뮤니티 · 끼루 AI 답글 (`domain/community`)

- 종목 토론 게시글·댓글·반응·신고·관심종목, 관리자 신고 처리.
- 게시글에 끼루를 멘션하면 AI가 공시(OpenDART)·재무·배당·위험등급 등 도구로 근거를 모아 답글을 단다(`kkiru/*Tool`, `EvidenceCollector`, `KkiruAnswerValidator`).
- **동시성 처리**
  - 하루 전체 AI 호출 상한 확인을 한 번에 하나씩 처리: `KkiruCallGate.java:17` (`SELECT … FOR UPDATE`로 게이트 행 잠금)
  - 글당 호출 한도: 끼루를 멘션한 댓글이 동시에 들어와도 한도를 넘지 않도록 그 경우에만 게시글 행을 잠근다 (`CommunityCommentServiceImpl.java:53` → `CommunityPostRepository.java:20`, `@Lock(PESSIMISTIC_WRITE)`)
  - AI 호출은 `@Async`(`CommunityAsyncConfig`)로 요청 스레드와 분리 (큐 포화 통합 테스트 `KkiruQueueFullIntegrationTest`)
  - 서버 재시작 등으로 PENDING에 남은 호출은 `KkiruPendingSweeper`가 FAILED로 바꿔 다시 물을 수 있게 한다. 조건부 UPDATE라 여러 번 실행해도 안전하다

## 팀 공통

- CI/CD: `.github/workflows/ci.yml`(테스트), `deploy.yml`(이미지 빌드 → ghcr.io → 서버 배포, 태그 지정 롤백), `Dockerfile`
- 서버 구성과 배포 스크립트: [maria-deploy](https://github.com/kminseok-dev/maria-deploy) (내가 작성)

## 실행

```bash
# 1) MariaDB에 db/01_ria_ddl.sql로 스키마 생성 (+ 시드)
# 2) 환경변수: DB_URL, DB_USERNAME, DB_PASSWORD, REDIS_HOST, REDIS_PASSWORD, JWT_SECRET,
#    OPENAI_API_KEY, EXIM_API_KEY, KIS_APP_KEY, KIS_APP_SECRET 등 (src/main/resources/application.yml 참고)
./gradlew bootRun
./gradlew test
```

mydata·return-securities·risk가 함께 떠 있어야 전체 흐름이 동작한다.
