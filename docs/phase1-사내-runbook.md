# Phase 1 사내 런북 — Oracle 연결 (Spring 판)

> Node 판 `fdc-agent-be/docs/phase1-사내-runbook.md` 를 이 레포(Java) 경로로
> 각색한 사본. Node 판은 deprecated·사내 이관 제외라 절차 문서는 여기가 현행.

목표: **사내 토큰을 거의 안 쓰고** 정형 조회를 실 Oracle 로 켠다. 설계/코드는
외부에서 이미 끝났고, 사내에선 아래를 **순서대로 복붙 실행**하면 된다.

핵심 원칙: 실 스키마는 `src/main/java/fdc/agent/data/oracle/SchemaMap.java`
**한 파일**에만 채운다. SQL 로직은 손대지 않는다.

## 0. 사전 준비

- 이 레포를 **사내 github 로 이전**. 실 스키마는 사내에만 둔다.
- JDK 21 (Temurin/Corretto) — 시스템 java 가 21 이 아니면 `JAVA_HOME` 지정
  (toolchain 자동 다운로드 미구성). Oracle 접속은 ojdbc11 thin — Instant Client 불필요.
- read-only 계정으로 env 작성 (`.env` 자동 로드는 없음 — `export` 또는 compose `env_file`):

  ```sh
  DATA_SOURCE=fixture          # 아직 fixture 로 둔다 (§6에서 전환)
  ORACLE_USER=fdc_ro
  ORACLE_PASSWORD=...
  ORACLE_CONNECT_STRING=host:1521/service
  ```

- `agent-db-plugin` 이 같은 DB 에 연결돼 있어야 한다 (`list_connections` 로 확인).

## 1. 스키마 탐색 (이미 테이블을 알면 건너뜀)

`agent-db-plugin` 으로 설비/챔버/센서/셋업이벤트 테이블을 찾는다:

- `list_tables` — 후보 테이블 훑기 (이름으로 EQP/CHAMBER/SENSOR 류 식별)
- `describe_table <TABLE>` — 각 테이블의 컬럼·타입·PK/FK 확인

확인할 것:

| 계약 대상 | 필요한 것 |
| --- | --- |
| 설비 마스터 | 테이블, id/name/model 컬럼, 노출할 값 컬럼들 |
| 챔버 | 테이블, id 컬럼, 설비 FK 컬럼 |
| 센서 | 테이블, id 컬럼, 설비 FK 컬럼 |
| 셋업 이벤트 | 테이블, 설비 FK, 시각 컬럼, 타입 코드 컬럼(+코드 의미) |

## 2. SchemaMap.java 채우기 (핵심)

`src/main/java/fdc/agent/data/oracle/SchemaMap.java` 의 `TODO_` 예시값을 §1 에서
확인한 실명으로 치환한다. `valueLabels` 는 화면 표시명(친화적 라벨),
`setupEvent.typeCodeMap` 은 DB 코드값 → 계약 타입 매핑:

```java
static final SetupEventMap SETUP_EVENT = new SetupEventMap(
    "FDC_SETUP_HIST",          // table
    "EQP_ID",                  // equipmentFkCol
    "EVENT_DT",                // timeCol
    "EVENT_TYPE",              // typeCol
    "EVENT_DESC",              // labelCol
    Map.of("S", "setup", "I", "info_change", "M", "maintenance"));
```

주의: 값은 SQL 에 **식별자로 삽입**된다. 반드시 실제 컬럼명만(사용자 입력 금지).
`assertIdent` 화이트리스트(A-Z0-9_$#, 숫자 시작 불가)를 통과해야 한다.

## 3. 검증 — 식별자 테스트

```sh
./gradlew test
```

`SchemaMapTest`/`assertIdent` 가 SchemaMap 의 모든 식별자를 화이트리스트
검증한다. 실명에 공백·특수문자가 있으면 여기서 걸린다(그런 컬럼명은 없어야 정상).

## 4. 실 DB 스모크 (detail / peers / setup-events — 챗 경유)

정형 GET 은 제거됐으므로(2026-07-19) 스모크는 chat 엔드포인트로 — 에이전트
손툴이 같은 `EquipmentRepo` 조회를 태운다:

```sh
DATA_SOURCE=oracle ./gradlew bootRun
# 다른 셸에서 — 실제 설비 ID 로 (mock LLM 은 설비 ID 패턴을 감지해 툴 호출):
curl -sN -X POST -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"<실설비ID> 설비 정보 보여줘"}]}' \
  localhost:8080/api/fdc/v1/chat | head -40
```

`event: done` 페이로드의 tables 에 실 컬럼 라벨(`valueLabels`)과 실데이터
행이 실렸는지 확인. 주의: mock LLM 의 설비 ID 감지 패턴은
`[A-Z]{2,4}-\d{2,}` — 실 ID 형식이 다르면 `LLM_BASE_URL`(실 LLM)을 설정하고
스모크한다. Spring 판은 zod 런타임 parse 가 없다 — 형태는 contract
record 가 컴파일 타임에 강제하고, 매핑 오류(컬럼 오타·타입 불일치)는 SQL
예외나 null 값으로 드러난다. fixture 모드 응답과 눈으로 대조할 것.

## 5. ~~compare SQL 작성~~ — 해당 없음 (2026-07-19 제거)

정형 조회 4 GET(compare 포함)이 HTTP 표면에서 제거되어 **compare 분석 SQL
작업은 소멸**했다. 사내 실코딩 항목 없음 — §2 의 SchemaMap 치환이 전부다.
(부활 시 Node 판 runbook §5 + `fdc-agent-be` 구현 이력 참조.)

## 6. 전환 + FE 검증

```sh
# env 에서
DATA_SOURCE=oracle
```

프론트(demo-fe)를 `BACKEND_URL` 로 이 서버에 붙인 뒤:

- 챗으로 설비 상세·동종설비·셋업 이력을 물었을 때 **실데이터** 표가 뜨는지
- 응답 헤더 `x-fdc-data-source: oracle` 확인 (fixture → oracle 로 바뀜)
- (demo-fe 배지 PR 이 머지됐다면) 화면 배지가 `oracle` 로 표시

## 되돌리기

문제 시 env 를 `DATA_SOURCE=fixture` 로 되돌리면 즉시 mock 으로 복귀
(코드 변경 없음).
