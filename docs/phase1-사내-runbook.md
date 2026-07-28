# Phase 1 사내 런북 — Oracle 연결 (Spring 판)

목표: **사내 토큰을 거의 안 쓰고** 도메인 스킬 조회를 실 Oracle 로 켠다.
설계·코드는 외부에서 끝났고, 사내에선 아래를 **순서대로 복붙 실행**하면 된다.

핵심 원칙: Java 코드는 손대지 않는다. 실 스키마는 **스킬 spec 의 SQL**
(`steps[].sql`) 에만 나타나고, spec 은 akg(지식 허브)가 소유한다. 사내 작업 =
그 SQL 의 테이블·컬럼명을 실명으로 맞추는 것.

## 0. 사전 준비

- 이 레포를 **사내 github 로 이전**. 실 스키마는 사내에만 둔다.
- JDK 21 (Temurin/Corretto) — 시스템 java 가 21 이 아니면 `JAVA_HOME` 지정
  (toolchain 자동 다운로드 미구성). Oracle 접속은 ojdbc11 thin — Instant Client 불필요.
- read-only 계정으로 env 작성 (`.env` 자동 로드는 없음 — `export` 또는 compose `env_file`):

  ```sh
  DATA_SOURCE=fixture          # 아직 fixture 로 둔다 (§4에서 전환)
  ORACLE_USER=fdc_ro
  ORACLE_PASSWORD=...
  ORACLE_CONNECT_STRING=host:1521/service
  ```

- `agent-db-plugin` 이 같은 DB 에 연결돼 있어야 한다 (`list_connections` 로 확인).

## 1. 스킬 SQL 이 부르는 테이블 확인

번들 spec(`src/main/resources/skills/*.spec.json`)의 `steps[].sql` 이 참조하는
테이블·컬럼이 실 스키마에 그 이름으로 있는지 본다. 기본 세트는 다음 셋이다:

| 스킬 | 참조 테이블 |
| --- | --- |
| `fdc-explain-sensor` | `fdc_sensor` · `fdc_equipment` · `fdc_setup_event` |
| `fdc-trace-reading` | `fdc_sensor_reading` |

`agent-db-plugin` 으로 대조한다:

- `list_tables` — 후보 테이블 훑기
- `describe_table <TABLE>` — 컬럼·타입·PK/FK 확인

## 2. spec 의 SQL 을 실명으로 맞추기

이름이 다르면 **spec 을 고친다**(코드가 아니라). spec 의 진실원은 akg 이므로,
akg 쪽 도메인 스킬 문서에서 SQL 을 수정하고 다시 내려받는 것이 정식 경로다.

```sh
AKG_URL=http://<akg-host>:<port>   # 설정 시 런타임 fetch, 미설정 시 classpath 번들
```

`AKG_URL` 없이 번들만으로 급히 검증하려면 `src/main/resources/skills/*.spec.json`
을 직접 고쳐도 되지만, 그 수정은 akg 로 돌려놓아야 한다.

주의: bind 값은 항상 `:이름` 네임드 바인드로만 넘어간다. 식별자(테이블·컬럼)를
사용자 입력으로 조립하지 않는다.

## 3. 검증

```sh
./gradlew test
```

spec 로더가 `steps[].binds` 와 SQL 의 바인드 변수 일치를 검사한다 — SQL 에
`:eqp` 를 쓰는데 binds 에 없으면 여기서 걸린다.

## 4. 실 DB 스모크

```sh
DATA_SOURCE=oracle ./gradlew bootRun
# 다른 셸에서 — 실제 센서 ID 로:
curl -s -X POST localhost:8080/api/fdc/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"<실센서ID> 센서 설명해줘"}]}'
```

응답 SSE 의 `done` 페이로드에 스킬 스텝별 표가 실려 오면 성공이다. 매핑 오류
(컬럼 오타·타입 불일치)는 SQL 예외나 빈 표로 드러난다 — fixture 모드 응답과
눈으로 대조할 것.

응답 헤더 `x-fdc-data-source: oracle` 로 전환 여부를 확인한다.

## 5. FE 검증

프론트(demo-fe)를 `BACKEND_URL` 로 이 서버에 붙인 뒤, 채팅에서 스킬 질문이
실데이터로 답하는지 본다. 스킬로 닿지 않는 데이터는 **요청 카드**로 나온다 —
BE 가 직접 조회하지 않고 사용자에게 조달을 요청하는 것이 정상 동작이다.

## 되돌리기

문제 시 env 를 `DATA_SOURCE=fixture` 로 되돌리면 즉시 mock 으로 복귀
(코드 변경 없음).
