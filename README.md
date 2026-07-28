# fdc-agent-be-spring

[fdc-agent-be](https://github.com/ollybaysion/fdc-agent-be)(Node/TS·Fastify)의
**Java 21 + Spring Boot 포팅**. demo-fe 의 `/api/fdc/v1/*` 계약을 동일하게
구현한다 — FE 는 무수정, `BACKEND_URL` 오리진 스왑만으로 두 서버를 교체할 수
있다.

## 패리티 (역사 — 이제 진실원은 이 레포)

포팅의 합격 기준은 원본 Node 판과의 **응답 패리티**였고, 2026-07-17 기준
**24 케이스 전부 byte-identical** 로 통과했다(`messageId` 타임스탬프만 정규화).

그 뒤 두 판은 갈라졌다 — 스킬 spec v2(2026-07-21), 그리고 equipment 스택 제거
(2026-07-27). 같은 질문에 Node 는 설비 표를, 이 레포는 데이터 요청 카드를
돌려주므로 비교가 성립하지 않는다. 회귀 판정은 JUnit 스위트가 맡고,
`scripts/parity.sh` 에는 아직 비교 가능한 3케이스만 남아 있다.

핵심 포팅 규칙(승계):

- fixture 의 결정론 mock 은 JS 와 bit-호환(`util/Js.java` — 32-bit 오버플로
  해시, JS `Math.round` 의미론, 정수는 소수점 없이 직렬화).
- zod `.optional()` → `@JsonInclude(NON_NULL)`, `.nullable()`(matchedRun) →
  `ALWAYS`. JSON 키 순서는 record 선언 순서로 TS 객체 리터럴과 일치.
- SSE 는 `token* → done | error`, code point 단위 15ms 스트리밍, 에이전트
  실행은 스트리밍 전 완료(실패 시 정상 HTTP 에러).

## 스택 매핑

| Node 판 | Spring 판 |
| --- | --- |
| Fastify | Spring Boot 3.5 Web MVC + 가상 스레드 (SSE 는 blocking write) |
| zod contract | Java record + Jackson (`contract/`) |
| node-oracledb pool | HikariCP + `JdbcClient` (oracle 모드에서만 활성) |
| openai SDK → 온프렘 GW | `java.net.http.HttpClient` 직구현 (`llm/OpenAiLlm`) |
| env.ts fail-fast | `@ConfigurationProperties` (`config/AppProps`) |
| vitest `.inject()` | JUnit 5 + MockMvc (44 테스트) |

시임(seam)은 원본과 동일: `DATA_SOURCE`(fixture↔oracle), `LLM_BASE_URL`
(mock↔openai), 스킬 spec.json 은 **언어 중립 진실원**으로 그대로 복사해
사용(`src/main/resources/skills/`).

## 실행

```bash
# JDK 21 필요 (Temurin/Corretto 권장 — Oracle JDK 는 라이선스 주의).
# toolchain 자동 다운로드는 미구성 — 시스템 java 가 21 이 아니면 JAVA_HOME 지정:
export JAVA_HOME=/path/to/jdk-21
./gradlew bootRun          # fixture + mock LLM, :8080
./gradlew test             # 테스트 44개
```

환경 변수(이름은 Node 판 `.env` 계약 그대로):

```text
PORT=8080  HOST=0.0.0.0  NODE_ENV=development|production  LOG_LEVEL=info
DATA_SOURCE=fixture|oracle
ORACLE_USER / ORACLE_PASSWORD / ORACLE_CONNECT_STRING   # host:port/service
ORACLE_POOL_MIN=2 / ORACLE_POOL_MAX=10
LLM_BASE_URL=http://llm-gw.internal/v1   # 미설정 시 결정적 mock
LLM_API_KEY / LLM_MODEL
TRACE_LOG_LEVEL=info                     # FE→BE→LLM 왕복 트레이스, off 로 끔
LOG_FILE=logs/fdc-agent-be.log           # 로그 파일 위치(콘솔에도 그대로 나간다)
```

demo-fe 쪽은 `BACKEND_URL=http://<host>:8080` — **오리진만**, `/api/fdc/v1`
붙이지 말 것.

## 왕복 트레이스 로그

한 요청이 FE→BE→LLM 을 어떻게 오갔는지 로거 `fdc.trace` 하나로 다 남는다
(기본 켜짐, `TRACE_LOG_LEVEL=off` 로 끔). 콘솔과 `logs/fdc-agent-be.log`
(`LOG_FILE` 로 변경) 양쪽에 나가고, 줄들은 `X-Request-Id` 로 묶인다.

| 블록 | 무엇을 보여주나 |
| --- | --- |
| `FE→BE 요청` | 받은 본문 전량 — messages·context·timeRange·scope·inputs·dataSnapshots(행은 앞 20개) |
| `BE→LLM 툴 노출 (N개)` | 이번 요청에서 LLM 이 실제로 보는 툴 전량(이름·설명·파라미터 스키마) |
| `BE→LLM step N 메시지` | 그 스텝에 보낸 메시지 배열(시스템 프롬프트·맥락 섹션·툴 결과 포함) |
| `BE→LLM HTTP POST` | 전선에 나가는 JSON 원문(`tools[].function` 포함, 인증 헤더는 제외) |
| `LLM→BE HTTP <status>` | GW 응답 원문 |
| `툴 실행 <name>` | LLM 이 채운 인자 · 되먹인 요약 · 결과 표의 모양 |
| `BE→FE 응답` | 최종 텍스트 + done 페이로드(tables·dataRequests·inputRequests·추천질문) |

`query_snapshot` 은 붙여넣은 표가 있을 때만 툴 목록에 오른다 — 트레이스에
없으면 모델이 안 부른 게 아니라 **부를 수 없었던** 것이다.

## HTTP 표면

- `GET /health`
- `POST /api/fdc/v1/chat` — SSE `token* → done | error`

정형 조회 GET 은 없다. 설비·챔버·센서를 포함해 모든 데이터는 **도메인 스킬
툴로 조회**하고, 스킬로 닿지 않으면 `done.dataRequests` 로 사용자에게 조달을
요청한다(BE 가 값을 지어내지 않는다).

## 사내 이관 포인트

1. 스킬 spec 의 `steps[].sql` — 테이블·컬럼을 실 스키마 이름으로 맞춘다.
   spec 의 진실원은 akg 이므로 거기서 고쳐 내려받는 것이 정식 경로
   (`AKG_URL` 미설정 시 `src/main/resources/skills/` 번들 사용).
2. `DATA_SOURCE=oracle` + `ORACLE_*` — read-only 계정으로 전환.
3. `LLM_BASE_URL/KEY/MODEL` — 온프렘 OpenAI 호환 GW 로 설정.

절차 상세 = `docs/phase1-사내-runbook.md`.
