# 분기 판정 LLM 프롬프트 — spec `branches`

`POST {LLM_BASE_URL}/chat/completions` 요청 본문 그대로다(실 spec fdc-trace-reading,
`CNT = 0` 케이스). 읽기 좋게 content 안의 `\n` 만 실제 개행으로 풀어 적었다 —
전송 시엔 한 줄 문자열이다. 메시지는 판정 본문 **user 하나뿐** — 대화 이력을
싣지 않는다(분기 판정의 근거는 표의 값뿐이라 질문이 판정에 영향을 주면 안 된다).
system 메시지·`tools` 필드도 없음. 응답은 `choices[0].message.content`.

```text
{
  "model": "<LLM_MODEL 설정값>",
  "messages": [
    { "role": "user", "content": "
# 조회 절차 분기 판단

절차를 진행하다 분기 조건이 있는 단계의 데이터가 도착했다. 아래 결과를 보고
성립하는 분기가 있으면 그 효과대로 가고, 없으면 다음 단계로 계속한다.

## 절차

- 스킬: fdc-trace-reading (센서 측정값)
- 시작 인자: equipment=CVD-01, param_index=7, start=2026-07-01, end=2026-07-31
- 도착한 단계: 1단계 "구간 측정 집계"

## 1단계 결과

| CNT | MEAN | SD | MINV | MAXV | ANOM |
| --- | --- | --- | --- | --- | --- |
| 0 | (null) | (null) | (null) | (null) | 0 |

## 분기 목록 (0부터 번호)

0. 조건: CNT = 0 → 효과: 종료 — "그 기간·PARAM_INDEX로는 측정이 없다"로 답한다 (없는 값을 지어내지 않는다)

## 지시

위 표를 근거로 각 조건의 성립 여부를 판단하라. 반드시 아래 JSON 한 줄로만 답하라
(다른 문장·설명·코드펜스 금지):

- 성립한 분기의 효과가 종료다: {"decision":"stop","index":<번호>,"reason":"<근거 한 줄>"}
- 성립한 분기의 효과가 단계 열림이다: {"decision":"open","index":<번호>,"reason":"<근거 한 줄>"}
- 성립하는 분기가 없다(또는 애매하다): {"decision":"continue"}

## 규칙

- 조건 문장은 정해진 문법 없이 사람 말로 적혀 있다 — 문구 형태가 아니라 뜻으로 평가한다.
- 성립 근거는 위 표의 값뿐이다 — 표에 없는 값을 가정하지 않는다.
- decision 종류는 성립한 분기에 적힌 효과를 그대로 따른다 — 종료면 stop, 열림이면 open.
- 조금이라도 애매하면 continue 다. 분기는 확실할 때만 탄다.
- 여러 분기가 성립하면 번호가 빠른 것 하나만.
- reason 은 화면에 그대로 표시된다 — 한국어 한 문장, 존댓말.
" }
  ],
  "temperature": 0,
  "stream": false
}
```

기대 응답:

```json
{"decision":"stop","index":0,"reason":"집계 결과 CNT가 0건입니다 — 그 기간·PARAM_INDEX로는 측정이 없습니다."}
```

같은 표에서 CNT=412 라면 `{"decision":"continue"}`.

## 열림형 발췌 (조건부 단계를 여는 분기)

분기 목록이 이렇게 적혀 있고(가상 — 이탈 상세 단계가 spec 에 추가됐다고 가정):

```text
## 1단계 결과

| CNT | MEAN | SD | MINV | MAXV | ANOM |
| --- | --- | --- | --- | --- | --- |
| 412 | 92.4 | 2.77 | 83.2 | 103.5 | 57 |

## 분기 목록 (0부터 번호)

0. 조건: ANOM > 0 → 효과: 열림 — 4단계 "이탈 상세 조회"를 연다
```

기대 응답:

```json
{"decision":"open","index":0,"reason":"스펙 이탈이 57건 있습니다 — 이탈 상세 조회가 필요합니다."}
```
