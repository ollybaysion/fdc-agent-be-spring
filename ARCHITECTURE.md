# FDC Agent Backend — 설계 문서 (v2, Java Spring 기준)

`demo-fe`(github.com/ollybaysion/demo-fe)의 `/api/fdc/v1/*` 계약을 구현하는
별도 백엔드 서비스. FE는 무수정, `BACKEND_URL` 프록시로 붙는다.
**기준 구현 = 이 레포(`fdc-agent-be-spring`, Java 21 + Spring Boot)**.

> **DEPRECATED**: 기존 Node/TS 판(`fdc-agent-be`)은 **2026-07-18부로 deprecated**.
> 신규 개발 금지 — 남은 용도는 계약 레퍼런스(zod 스키마)와 패리티 대조뿐.
> 기능 추가·수정은 전부 이 레포에서 한다.

확정 전제 (2026-07):

- **데이터 원천** = 사내 Oracle 19c 직접 조회 (read-only 계정)
- **챗 LLM** = 사내/온프렘 LLM (설비 데이터 외부 반출 금지, OpenAI 호환 GW)
- **형태** = 별도 서비스 + Next 프록시
- **스택** = **Java 21 + Spring Boot 3.5** (2026-07-18 확정, v1의 Node/TS 대체)

---

## 1. 기술 스택

| 레이어 | 선택 | 이유 |
| --- | --- | --- |
| 런타임 | **Java 21 LTS** (Temurin/Corretto) | 가상 스레드로 blocking SSE·에이전트 루프 단순화. Oracle JDK 회피(NFTC 무료 2026-09 종료 — 라이선스 리스크 0인 OpenJDK 빌드) |
| 프레임워크 | **Spring Boot 3.5 · Web MVC** | 사내 표준 친화. chat 도 에이전트 실행 완료 후 스트리밍이라 WebFlux 불필요 — MVC+가상 스레드로 충분 |
| 빌드 | **Gradle 8** (wrapper) | wrapper 는 Gradle 버전만 고정 — **JDK 21 은 별도 설치 + `JAVA_HOME` 지정 필요**(toolchain 자동 다운로드 미구성) |
| 계약(DTO) | **Java record + Jackson** | zod 계약과 1:1 — 선언 순서=JSON 키 순서, `@JsonInclude(NON_NULL)`=optional, `ALWAYS`=nullable(matchedRun). 숫자는 `Number`로 JS 표기 유지 |
| Oracle 접근 | **ojdbc11(thin) + HikariCP + `JdbcClient`** | thin = Instant Client 불필요·19c 호환. 네임드 바인드(`:id`)가 스킬 spec SQL과 1:1. read-only 정형 SQL이라 JPA 불채용 |
| LLM 클라이언트 | **`java.net.http` 직구현** (OpenAiLlm) | OpenAI 호환 온프렘 GW 대응. 툴 루프(MAX_STEPS=4)가 작고 명시적이라 프레임워크(Spring AI) 없이 동작 보존 우선. mock ↔ openai seam 유지 |
| 검증·에러 | 컨트롤러 명시 검증 + `@RestControllerAdvice`(`ResponseEntityExceptionHandler` 상속) | 입력 캡(100/10k) 재적용, API.md 에러코드 미러링, prod 5xx 상세 마스킹. 상속하는 이유 = advice 가 MVC 기본 처리기보다 먼저 돌아 **405·415 같은 프레임워크 예외가 포괄 핸들러에 삼켜져 500 이 되던 것**(#45); 부모의 ProblemDetail 본문은 `handleExceptionInternal` 이 `{error, message?}` 로 되돌린다 |
| 로깅 | **SLF4J/Logback + MDC** | `X-Request-Id` 전파. 요청 헤더는 애초에 로깅 안 함 — 로깅 확장 시 민감 헤더 마스킹 필수 |
| 테스트 | **JUnit 5 + Spring Boot Test/MockMvc** | vitest 스위트에서 출발해 이제는 Spring 판이 기준. MockMvc = Fastify `.inject()` 대응 |
| 회귀 판정 | **JUnit 스위트**. `scripts/parity.sh` 는 잔여 3케이스만 | Node 대조군은 사실상 소진 — spec v2(2026-07-21)와 equipment 스택 제거(2026-07-27)로 두 판의 응답이 갈렸다 |

**참고 — v1(Node/TS) 스택**: Node 22·TS 5·pnpm·Fastify·node-oracledb·zod·pino·vitest.
이 스택으로 Phase 0~2를 구축·검증한 뒤 2026-07-17 Spring 으로 전체 포팅 — 선정 근거는
위 표의 각 행이 승계한다. 원문은 `fdc-agent-be/ARCHITECTURE.md`(v1, 역사 보존).

---

## 2. 레포 구조 · 계약 전략

단일 Gradle 모듈. Spring **컨테이너(빈·어노테이션)**가 관여하는 곳은
**config/·web/ 두 패키지뿐** — 나머지는 스프링 유틸 클래스를 라이브러리로
쓸 뿐(`JdbcClient`, 스킬 리소스 스캐너) 컨테이너 없이 `new` 로 조립·테스트한다.

```text
fdc-agent-be-spring/
├── build.gradle · settings.gradle · gradlew   # Gradle 8 wrapper
└── src/
    ├── main/java/fdc/agent/
    │   ├── FdcAgentBeApplication.java  # 진입점 (DataSource 자동설정 제외)
    │   ├── config/    # AppProps(env 계약) · DataConfig(seam 배선) · OracleConfig(조건부 풀)
    │   │              # RequestIdFilter · ApiException(Handler)
    │   ├── api/       # ChatController(SSE) · Health
    │   ├── contract/  # 계약 record — ChatTable·ChatDataSnapshot·DataRequest·DonePayload…
    │   ├── chat/      # ChatAgent(툴 루프) · ChatPrompt(프롬프트 조립) · AgentTool
    │   │              # 툴 구현: SnapshotQueryTool · DataRequestTool · InputRequestTool
    │   │              # SnapshotDb(붙여넣은 표 → 임시 SQLite)
    │   │              # QueryKey·QueryProgress(조달 왕복의 조회 키 = 절차 진행)
    │   ├── akg/      # AkgSource — 허브 fetch 소스 공통 계약(스킬·라인)·리로드 결과
    │   ├── llm/       # LlmClient seam(LlmTypes 내 인터페이스): MockLlm ↔ OpenAiLlm
    │   ├── skills/    # SkillLoader·SkillRegistry — spec.json → 에이전트 툴 컴파일
    │   │              # QueryPool(요청 가능한 조회 목록) · SqlRender(리터럴 SQL 렌더)
    │   └── util/Js.java  # JS bit-parity 헬퍼 (해시·반올림·수 표기)
    ├── main/resources/
    │   ├── application.yml            # env 이름 계약 유지 (DATA_SOURCE/ORACLE_*/LLM_*)
    │   └── skills/*.spec.json         # ★ spec v2 (형식 진실원 = agent-knowledge-governance)
    ├── test/java/fdc/agent/           # JUnit 110
    ├── scripts/parity.sh              # Node 대조군 잔여 3케이스
    ├── scripts/{start,stop}.sh        # 백그라운드 기동·종료 (PID 파일 = logs/*.pid)
    ├── scripts/reload.sh              # POST /admin/reload 호출
    └── docs/phase1-사내-runbook.md    # Oracle 연결 절차 (사내 단계)
```

### 계약 전략 — 3중 안전망

API 계약의 프로즈 원본은 demo-fe `API.md`+`types.ts`. Spring 판의 형태 보장은:

1. **contract record** — 구조를 타입으로 강제 (Jackson 직렬화 규칙이 zod 의미론 재현)
2. **JUnit 계약 테스트** — 경로·에러코드·SSE 계약
3. ~~패리티 하네스~~ — 소진. 두 판의 응답이 갈려 더는 대조군이 아니다
   (스킬은 2026-07-21 spec v2, 설비 조회는 2026-07-27 제거).

도메인 스킬 추가는 코드 0줄 — `resources/skills/`에 spec.json 하나를 떨구면
기동 시 자동 스캔·컴파일된다. `description` 은 spec 필드가 아니라
`scope`+`focus`+`inputs` 에서 로더가 합성하고(SkillLoader.synthesizeDescription),
툴 인자는 `spec.inputs`, bind 배선은 `steps[].binds` 가 소유한다.
단 oracle 모드 기준 — fixture 데모에서 새 테이블을 조회하려면
`SkillRegistry.FIXTURE_SKILL_QUERY` seed 보강이 필요하다.

### BE → LLM 경계 — 누가 무엇을 소유하나

한 요청에서 LLM 에 나가는 것은 세 가지다. **셋의 주인이 각각 다르다.**

| 나가는 것 | 주인 | 규율 |
| --- | --- | --- |
| 툴 정의(name·parameters·description) | 각 `AgentTool` | 스킬 툴은 spec 이 컴파일된 결과 |
| 툴 사용 규칙 | 각 `AgentTool.guidance()` | **붙은 툴의 규칙만** 시스템 프롬프트에 실린다 |
| 맥락 섹션(질의 대상·첨부·입력) | `ChatPrompt` | 마지막 사용자 메시지 앞의 별도 system 메시지 |

`ChatAgent` 는 이 중 무엇도 적지 않는다 — 툴을 모아 프롬프트를 받고, LLM 이 부른
이름으로 툴을 찾아 실행하고, 요약을 되먹이는 루프일 뿐이다.

규칙을 툴에 붙여 둔 이유는 **툴이 빠지면 규칙도 같이 빠져야** 하기 때문이다.
`query_snapshot` 은 붙여넣은 표가 있을 때만 등록되는데, 규칙이 시스템 프롬프트
상수에 있으면 표가 없는 요청에도 "그 툴로 조회하라"가 남는다. 같은 이유로 맥락
섹션은 특정 스킬 이름을 적지 않는다 — 스킬 목록은 akg 허브에서 런타임에 온다.

**툴은 두 갈래다.** 조회 툴(스킬 컴파일 결과·`query_snapshot`)은 실제로 데이터를
가져오고, 수집 툴(`request_data`·`request_input`)은 실행하지 않고 "이게 필요하다"를
모아 done 페이로드로 내보낸다. 루프는 둘을 구분하지 않는다. 같은 데이터를 무한히
다시 요청하지 않도록 하는 **억제는 수집 툴이 결정론적으로 확정**한다(모델 판단에
기대지 않는다). 수집 툴 인스턴스는 요청 단위다.

### 조달 왕복 — 풀에서 고르고, 도착에서 이어간다

BE 가 DB 에 닿지 못하는 배포에서는 조회가 왕복이 된다: 요청 카드 → 사용자가 사내에서
실행 → 결과 붙여넣기 → 다음 질문에 실려 옴. 이 왕복의 규율은 셋이다.

**① 요청은 풀 안에서만.** `QueryPool` 은 로드된 스킬 spec 의 `steps[]` 를 평면화한
목록이고, `request_data` 의 `queryId` 는 그 목록의 **닫힌 enum** 이다. 모델은 고르고
인자만 채운다 — 실행 문장(`SqlRender`)·조회 키(`QueryKey`)·기대 컬럼은 BE 가 만든다.
렌더된 SQL 은 **사용자가 자기 권한으로 실행**하므로 이스케이프는 미관이 아니라 경계다.
같은 spec 이 조회 툴로도 컴파일되므로, 스킬을 등재하면 실행과 조달이 함께 늘고 등재되지
않은 조회는 어느 쪽으로도 나가지 않는다. 풀이 비면 `request_data` 자체가 안 붙는다.

**② 진행은 저장하지 않고 도착에서 유도한다.** `queryKey = {스킬}#{단계}__{필수인자}` 라
한 절차의 모든 단계가 같은 run 이름표를 단다. 이번 요청에 실려 온 스냅샷의 키만 풀에
비추면 "어디까지 왔고 다음이 무엇인지"가 나오고(`QueryProgress`), 그 한 걸음이 맥락에
실려 모델을 민다. 계약에 진행 필드도, 서버 세션도 필요 없다. 앞 단계 결과를 바인드로
쓰는 단계는 **도착한 표에서 값을 읽는다** — 여러 행이면 모델이 `pick` 으로 고르되
그 컬럼에 실제로 있는 값 중에서만 고른다(갈림길은 맡기고 창작은 막는다).

**③ "없음"은 사실이다.** `ChatDataSnapshot` 은 세 상태다 — 행 있음 / **조회 결과 0행** /
아직 안 옴. 0행은 "그 데이터는 없다"는 답의 근거이고 그 절차는 거기서 정상 종료된다.
억제 기준도 "키가 있나"가 아니라 **"도착했나"** 다: 키만 있고 내용이 안 온 항목은 다시
요청할 수 있어야 한다. 스킬 실행기도 같은 규율을 따라 *앞 단계가 없어 실행하지 못한
스텝*과 *실행했는데 0행인 스텝*을 갈라 적는다.

### HTTP 표면 · 문서 포인터

- `GET /health`
- `POST /admin/reload` — akg 소스(스킬·라인) 강제 리로드(`scripts/reload.sh`).
  운영 표면이라 제품 문법 밖(`/health` 층). 섹션마다 `source`·`outcome`·`count`
  를 돌려준다. 리로드는 fail-open 이라 실패해도 예외 없이 옛 스냅샷을 계속
  서빙하므로, **`outcome` 이 그 사실을 말해주지 않으면 반영 실패가 성공처럼
  보인다** — `fetched`(받아왔다) / `hub-unreachable`(못 닿아 유지 중) /
  `already-refreshing`(주기 refresh 가 이미 진행 중) / `not-configured`
  (akg 미구성)로 가른다.
- `POST /api/fdc/v1/chat` — SSE `token* → done | error`. 에이전트 실행은
  스트리밍 전 완료(실패는 정상 HTTP 에러로).
- `POST /api/fdc/v1/chat/data` — 데이터 패널 판정 인렛(panel-judge, #38). 패널의
  수정·입력마다 호출되고 `PanelJudge` 가 무상태 결정론으로 판정한다: 바인드가 준비된
  미도착 스텝 전부를 SQL 완성본 카드(`openRequests`, 전체 리컨사일)로, 갈림길은
  `needsPick` 후보 선언으로, 절차 종결 전이만 그 응답의 SSE `token*` 으로 최종 서술
  (LLM 은 서술 한 곳에만 남는다). 대화 인렛은 전부 `/chat/*` 네임스페이스다.
- `GET /api/fdc/v1/skills` — 사람이 고르는 스킬 카탈로그(로드된 spec 목록).
- `GET /api/fdc/v1/lines` — 설비 카드 라인 드롭다운 목록(akg `fab-line`,
  미구성이면 빈 목록).
- 데이터를 돌려주는 정형 조회 GET 은 없다. 설비·챔버·센서를 포함해 **모든 데이터는
  스킬 툴로 조회하거나, 닿지 않으면 `dataRequests` 로 사용자에게 조달을 요청**한다.
  조달 요청도 등재된 스킬 spec 의 조회만 나간다(임의 SQL 없음).
- 빌드·기동 절차 = `README.md`, 계약 프로즈 원본 = demo-fe
  `API.md`+`types.ts`, Oracle 연결 절차 = `docs/phase1-사내-runbook.md`.

---

## 3. 설정 / 환경 변수

백엔드(런타임 주입, 이미지에 안 굽는다). 이름 계약은 Node 판과 동일 —
`application.yml` placeholder 가 매핑하므로 같은 `.env` 로 두 서버를 띄울 수 있다:

```text
PORT=8080  ·  HOST=0.0.0.0  ·  NODE_ENV  ·  LOG_LEVEL
DATA_SOURCE=fixture|oracle          # 데이터 seam (fixture = 결정론 mock)
# Oracle (read-only 계정) — oracle 모드에서만 필수
ORACLE_USER / ORACLE_PASSWORD / ORACLE_CONNECT_STRING   # host:port/service
ORACLE_POOL_MIN=2 / ORACLE_POOL_MAX=10
# 온프렘 LLM (OpenAI 호환) — 미설정 시 결정적 mock LLM
LLM_BASE_URL=http://llm-gw.internal/v1
LLM_API_KEY=... · LLM_MODEL=...
LLM_TIMEOUT_SECONDS=60              # 한 호출 응답 대기 상한(연결은 10초 고정)
# 채팅 타이핑 연출 — 간격, 그리고 연출이 응답을 붙드는 총 시간의 상한
CHAT_TOKEN_INTERVAL_MS=15 · CHAT_MAX_STREAM_DELAY_MS=3000
# akg 지식 허브(#8) — 미설정 시 classpath 번들 스킬
AKG_URL=... · AKG_TOKEN=... · AKG_REFRESH_SECONDS=300
```

> `.env` 파일 자동 로드는 양 서버 모두 없음 — shell `export` 나 compose
> `env_file` 로 주입한다.

**프론트엔드**(demo-fe)는 딱 하나:

```text
BACKEND_URL=http://fdc-agent-be:8080     # ★ origin만! /api/fdc/v1 붙이지 말 것
```

> 근거: `backend.ts`의 `new URL(apiPath, BACKEND_URL)`에서 `apiPath`가 이미
> `/api/fdc/v1/...`를 포함. `BACKEND_URL`은 오리진(스킴+호스트+포트)만.

---

## 4. 배포 토폴로지

```text
[브라우저] ─same-origin─► [demo-fe (Next)] ─BACKEND_URL─► [fdc-agent-be-spring]
                                                            │  ├─► Oracle 19c (RO)
                                                            │  └─► 사내 LLM GW (chat)
                          └──────────────── 전부 사내망 ────────────────┘
```

- 목표 = 2 컨테이너(FE·BE) — docker-compose(로컬)/k8s(운영). 내부 DNS로 프록시.
  **컨테이너화는 미착수(계획)** — Dockerfile/compose 는 아직 없다(사내 단계).
- 브라우저는 FE와만 통신(same-origin) → **CORS 불필요**.
- BE만 Oracle·LLM 도달. 브라우저는 둘 다 못 봄(반출 차단 자연 충족).
- 런타임 이미지 = `eclipse-temurin:21-jre` 계열 예정.

---

## 5. 알려진 갭 / 선행 작업 (상태 현행화 2026-07-18)

1. ~~FE chat route forward 미구현~~ — **해소** (demo-fe#125 머지, backend SSE pipe).
2. ~~온프렘 모델 tool calling 지원 여부~~ — **해소** (Node·Spring 양판 모두
   실 Claude 로 툴 루프 end-to-end 실증 — Spring 판은 FE 연동 라이브 데모
   2026-07-17. 사내 GW 는 env 3개만 교체).
3. **Oracle 연결 = 사내 남음, 작업은 한 곳** — 스킬 spec 의 `steps[].sql` 이
   참조하는 테이블·컬럼을 실명으로 맞추는 것뿐이다(Java 코드 수정 없음).
   절차 = 이 레포 `docs/phase1-사내-runbook.md`.

---

## 6. 빌드 이력 · 계획

| Phase | 내용 | Oracle | LLM | 상태 |
| --- | --- | --- | --- | --- |
| 0 | contract + 스켈레톤 + fixtures + FE 배선 + chat forward | ✗(fixture) | ✗ | **완료** |
| 1 | 정형 4 GET Oracle DAO | ✓ | ✗ | **폐기 2026-07-27** — 조회는 스킬 + 데이터 요청으로 일원화 |
| 2 | chat 에이전트 + skill-loader + 폼 분석 + 후속질문 | ✓ | ✓ | **완료** (실 Claude 입증) |
| — | **Spring 전체 포팅** (패리티 24 byte-identical + JUnit 31) | ✓ | ✓ | **완료** — 이후 기준 구현 |
| 3 | summary(LLM 요약) + upload(이미지, magic-byte 검증) | ✓ | ✓ | 미착수 — **이 레포에서 진행** |

Phase 0~2는 Node 판으로 구축·검증한 역사이고 산출물은 포팅으로 승계됐다.
Phase 3 이후 모든 신규 작업은 이 레포에서만 진행한다.

---

## 7. 보안 / 운영 (API.md 기준)

**구현됨** (코드로 확인 가능):

- 서버측 입력 캡(messages≤100, content≤10k) → `messages_too_many`/`message_content_too_long`.
- production 에서 SSE `error.message`·5xx body 에 stack/내부경로/DB메시지 금지
  (고정 문자열로 마스킹), 상세는 로그만.
- 모든 응답 `X-Request-Id`(+`X-Fdc-Data-Source`).
- Oracle: **read-only 계정 + 파라미터 바인딩(SQL injection 차단)**, 풀 상한.
  SQL 은 스킬 spec 이 소유하고 LLM 은 인자만 채운다(메뉴판 방식) — 식별자를
  사용자 입력으로 조립하는 경로가 없다.
- 요청 헤더는 로깅하지 않음(민감 헤더 노출 경로 자체가 없음 — 로깅 확장 시
  마스킹 필수).
- LLM GW 호출에 **연결 10초 · 응답 `LLM_TIMEOUT_SECONDS`(기본 60초) 상한** —
  GW 가 답을 안 줘도 요청 스레드가 풀린다(504).
- **GW 오류 본문은 클라이언트로 안 나간다** — 응답에는 상태 코드만, 본문은
  서버 로그에만(사내 GW 가 무엇을 실어 보낼지는 우리 소관이 아니다). env 무관.
- LLM 호출마다 `model·tools·소요 ms·usage(prompt/completion/total)` 를 로그로 —
  대화 한 건이 무엇을 얼마나 썼는지 서버에서 답할 수 있다.

**API.md 스펙 잔여 (미구현 — 사내 단계 TODO)**:

- 응답측 캡(rows≤1000, chart points≤5000, timeline≤500, 누적 chars≤100k)
  → `truncated` 표기. 현재 `finishReason:"length"` 는 MAX_STEPS 소진 표식일 뿐
  크기 캡이 아님.
- 인증(도입 시): `Authorization: Bearer`, 권한 `FDC.read`/`FDC.config`/`FDC.report`.

---

## 부록 — Deprecated Node/TS 판 (fdc-agent-be)

**DEPRECATED 2026-07-18.** 신규 개발 금지. Phase 0~2를 구축·검증한 원조 구현으로
모든 기능이 이 레포에 승계됨. 남은 용도:

- **계약 레퍼런스** — zod 스키마(`packages/contract`)·vitest 계약 테스트는 형태 논쟁 시 참고 원본.
- ~~패리티 대조군~~ — 소진. Spring 이 spec v2(2026-07-21)와 equipment 스택 제거
  (2026-07-27)로 갈라져, 같은 질문에 Node 는 표를 Spring 은 요청 카드를 돌려준다.

사내 이관 대상은 **이 레포 단독** — Node 판은 이관하지 않는다.
