# fdc-agent-be-spring

[fdc-agent-be](https://github.com/ollybaysion/fdc-agent-be)(Node/TS·Fastify)의
**Java 21 + Spring Boot 포팅**. demo-fe 의 `/api/fdc/v1/*` 계약을 동일하게
구현한다 — FE 는 무수정, `BACKEND_URL` 오리진 스왑만으로 두 서버를 교체할 수
있다.

## 패리티 (원본 = 진실원)

원본 Node 판이 계약의 진실원이며, 이 포팅의 합격 기준은 **응답 패리티**다.
두 서버를 나란히 띄우고(Node :8081, Spring :8080) 같은 요청을 보내 비교한
결과, 포팅 시점(2026-07-17) 기준 정형 4 GET + chat SSE **24 케이스 전부
byte-identical** (`messageId` 타임스탬프만 정규화).

> 2026-07-19 — 정형 조회 4 GET(`/equipment/*`)은 **HTTP 표면에서 제거**
> (설비 상세·비교를 챗 에이전트로 일원화, FE 패널 동시 제거). 현행 패리티
> 하네스는 chat+health 9케이스(`scripts/parity.sh`).

핵심 포팅 규칙:

- fixture 의 결정론 mock 은 JS 와 bit-호환(`util/Js.java` — 32-bit 오버플로
  해시, JS `Math.round` 의미론, 정수는 소수점 없이 직렬화).
- zod `.optional()` → `@JsonInclude(NON_NULL)`, `.nullable()` → `ALWAYS`.
  JSON 키 순서는 record 선언 순서로 TS 객체 리터럴과 일치.
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
| vitest `.inject()` | JUnit 5 + MockMvc (원본 1:1 포팅 후 정형 GET 분 제거, 23 테스트) |

시임(seam)은 원본과 동일: `DATA_SOURCE`(fixture↔oracle), `LLM_BASE_URL`
(mock↔openai), 스킬 spec.json 은 **언어 중립 진실원**으로 그대로 복사해
사용(`src/main/resources/skills/`).

## 실행

```bash
# JDK 21 필요 (Temurin/Corretto 권장 — Oracle JDK 는 라이선스 주의).
# toolchain 자동 다운로드는 미구성 — 시스템 java 가 21 이 아니면 JAVA_HOME 지정:
export JAVA_HOME=/path/to/jdk-21
./gradlew bootRun          # fixture + mock LLM, :8080
./gradlew test             # 테스트 23개
```

환경 변수(이름은 Node 판 `.env` 계약 그대로):

```text
PORT=8080  HOST=0.0.0.0  NODE_ENV=development|production  LOG_LEVEL=info
DATA_SOURCE=fixture|oracle
ORACLE_USER / ORACLE_PASSWORD / ORACLE_CONNECT_STRING   # host:port/service
ORACLE_POOL_MIN=2 / ORACLE_POOL_MAX=10
LLM_BASE_URL=http://llm-gw.internal/v1   # 미설정 시 결정적 mock
LLM_API_KEY / LLM_MODEL
```

demo-fe 쪽은 `BACKEND_URL=http://<host>:8080` — **오리진만**, `/api/fdc/v1`
붙이지 말 것.

## 패리티 재검증

```bash
# 터미널 1: Node 판
cd ../fdc-agent-be && PORT=8081 pnpm dev
# 터미널 2: Spring 판
./gradlew bootRun
# 터미널 3: 9케이스 하네스 — chat+health (SSE 는 messageId 만 정규화, jq 필요)
./scripts/parity.sh
```

## 사내 이관 포인트

1. `data/oracle/SchemaMap.java` — TODO_ placeholder 를 실 테이블/컬럼명으로
   치환(유일하게 채우는 파일, `assertIdent` 화이트리스트 검증).
2. `LLM_BASE_URL/KEY/MODEL` — 온프렘 OpenAI 호환 GW 로 설정.
3. 실 도메인 스킬 spec.json — `src/main/resources/skills/` 에 spec+wiring
   쌍을 떨구면 코드 수정 없이 툴로 등록(레포엔 데모 골든만).

(구 2번이던 compare Oracle SQL 은 정형 GET 제거로 작업 자체가 소멸.)
