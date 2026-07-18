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
| 계약(DTO) | **Java record + Jackson** | zod 계약과 1:1 — 선언 순서=JSON 키 순서, `@JsonInclude(NON_NULL)`=optional, `ALWAYS`=nullable. 숫자는 `Number`로 JS 표기 유지 |
| Oracle 접근 | **ojdbc11(thin) + HikariCP + `JdbcClient`** | thin = Instant Client 불필요·19c 호환. 네임드 바인드(`:id`)가 스킬 spec SQL과 1:1. read-only 정형 SQL이라 JPA 불채용 |
| LLM 클라이언트 | **`java.net.http` 직구현** (OpenAiLlm) | OpenAI 호환 온프렘 GW 대응. 툴 루프(MAX_STEPS=4)가 작고 명시적이라 프레임워크(Spring AI) 없이 동작 보존 우선. mock ↔ openai seam 유지 |
| 검증·에러 | 컨트롤러 명시 검증 + `@RestControllerAdvice` | 입력 캡(100/10k) 재적용, API.md 에러코드 미러링, prod 5xx 상세 마스킹 |
| 로깅 | **SLF4J/Logback + MDC** | `X-Request-Id` 전파. 요청 헤더는 애초에 로깅 안 함 — 로깅 확장 시 민감 헤더 마스킹 필수 |
| 테스트 | **JUnit 5 + Spring Boot Test/MockMvc** | vitest 스위트 1:1 포팅 후 정형 GET 분 제거(23 테스트). MockMvc = Fastify `.inject()` 대응 |
| 회귀 판정 | **패리티 하네스** = `scripts/parity.sh` (Node 판 병행 기동 + diff, 현행 9케이스) | 포팅 합격 기준선 = 24케이스 byte-identical(2026-07-17, 정형 GET 제거 전) |

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
    │   ├── web/       # ChatController(SSE) · HealthController
    │   ├── contract/  # 계약 record — EquipmentDetail·SetupEvent·ChatTable·DonePayload…
    │   ├── chat/      # ChatAgent(툴 루프) · EquipmentTools(손툴) · AgentTool
    │   ├── llm/       # LlmClient seam(LlmTypes 내 인터페이스): MockLlm ↔ OpenAiLlm
    │   ├── skills/    # SkillLoader·SkillRegistry — spec.json → 에이전트 툴 컴파일
    │   ├── data/      # EquipmentRepo seam: FixtureRepo ↔ OracleEquipmentRepo(+SchemaMap)
    │   └── util/Js.java  # JS bit-parity 헬퍼 (해시·반올림·수 표기)
    ├── main/resources/
    │   ├── application.yml            # env 이름 계약 유지 (DATA_SOURCE/ORACLE_*/LLM_*)
    │   └── skills/*.spec.json + *.wiring.json   # ★ 언어 중립 — Node 판과 동일 파일
    ├── test/java/fdc/agent/           # JUnit 23 (vitest 1:1 포팅 + 정형 GET 분 제거)
    ├── scripts/parity.sh              # 패리티 하네스 (chat+health 9케이스 diff)
    └── docs/phase1-사내-runbook.md    # Oracle 연결 절차 (사내 단계)
```

### 계약 전략 — 3중 안전망

API 계약의 프로즈 원본은 demo-fe `API.md`+`types.ts`. Spring 판의 형태 보장은:

1. **contract record** — 구조를 타입으로 강제 (Jackson 직렬화 규칙이 zod 의미론 재현)
2. **JUnit 계약 테스트** — 경로·에러코드·SSE 계약
3. **패리티 하네스** — Node 판과 응답 diff (byte-identical 기준선)

zod 스키마는 deprecated Node 판에 남아 3번의 대조군으로만 쓰인다.

도메인 스킬 추가는 코드 0줄 — `resources/skills/`에 spec.json+wiring.json
2파일을 떨구면 기동 시 자동 스캔·컴파일된다(agent-skill-foundry 산출물 접합점).
단 oracle 모드 기준 — fixture 데모에서 새 테이블을 조회하려면
`SkillRegistry.FIXTURE_SKILL_QUERY` seed 보강이 필요하다.

### HTTP 표면 · 문서 포인터

- `GET /health` · `POST /api/fdc/v1/chat` — SSE `token* → done | error`.
  에이전트 실행은 스트리밍 전 완료(실패는 정상 HTTP 에러로).
- **정형 조회 4 GET(`/equipment/{id}`·`/peers`·`/setup-events`·`/compare`)은
  2026-07-19 제거** — 설비 상세·비교는 챗 에이전트로 일원화(FE 패널도 동시
  제거). 데이터 접근(`EquipmentRepo` detail/peers/setup-events)은 에이전트
  손툴이 계속 사용하므로 남는다. 부활 시 git 이력·Node 판 참조.
- 빌드·기동·패리티 재검증 절차 = `README.md`, 계약 프로즈 원본 = demo-fe
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
3. **Oracle 연결 = 사내 남음, 작업은 한 곳** — `SchemaMap.java` `TODO_`
   치환뿐(에이전트 손툴의 detail/peers/setup-events 조회가 이것만으로 완성).
   compare 분석 SQL 은 정형 GET 제거(2026-07-19)와 함께 **작업 자체가 소멸**.
   절차 = 이 레포 `docs/phase1-사내-runbook.md`(Node 판 runbook 의 Java 각색 사본
   — §5 compare 절은 이제 해당 없음).

---

## 6. 빌드 이력 · 계획

| Phase | 내용 | Oracle | LLM | 상태 |
| --- | --- | --- | --- | --- |
| 0 | contract + 스켈레톤 + fixtures + FE 배선 + chat forward | ✗(fixture) | ✗ | **완료** |
| 1 | 정형 4 GET Oracle DAO | ✓ | ✗ | 골격 완료 후 **HTTP 표면 제거**(2026-07-19, 아래 행) |
| 2 | chat 에이전트 + skill-loader + 폼 분석 + 후속질문 | ✓ | ✓ | **완료** (실 Claude 입증) |
| — | **Spring 전체 포팅** (패리티 24 byte-identical + JUnit 31) | ✓ | ✓ | **완료** — 이후 기준 구현 |
| — | **정형 4 GET 제거** — 챗 일원화(FE 패널 동시 제거), compare SQL 사내 작업 소멸 | — | — | **완료** (2026-07-19) |
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
- Oracle: **read-only 계정 + 파라미터 바인딩(SQL injection 차단)** + 식별자
  화이트리스트(`assertIdent`), 풀 상한. LLM 은 SQL 텍스트 비노출(메뉴판 방식).
- 요청 헤더는 로깅하지 않음(민감 헤더 노출 경로 자체가 없음 — 로깅 확장 시
  마스킹 필수).

**API.md 스펙 잔여 (미구현 — 사내 단계 TODO)**:

- `context` 배열 캡(≤50).
- 응답측 캡(rows≤1000, chart points≤5000, timeline≤500, 누적 chars≤100k)
  → `truncated` 표기. 현재 `finishReason:"length"` 는 MAX_STEPS 소진 표식일 뿐
  크기 캡이 아님.
- 인증(도입 시): `Authorization: Bearer`, 권한 `FDC.read`/`FDC.config`/`FDC.report`.

---

## 부록 — Deprecated Node/TS 판 (fdc-agent-be)

**DEPRECATED 2026-07-18.** 신규 개발 금지. Phase 0~2를 구축·검증한 원조 구현으로
모든 기능이 이 레포에 승계됨. 남은 용도:

- **계약 레퍼런스** — zod 스키마(`packages/contract`)·vitest 계약 테스트는 형태 논쟁 시 참고 원본.
- **패리티 대조군** — 회귀 의심 시 두 서버를 나란히 띄워 diff (Node `:8081` ↔ Spring `:8080`).

사내 이관 대상은 **이 레포 단독** — Node 판은 이관하지 않는다.
