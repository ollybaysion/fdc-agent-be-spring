#!/bin/bash
# akg 소스(스킬·라인) 강제 리로드 — POST /admin/reload (#40).
#
# 허브에 domain-skill·fab-line 문서를 올리거나 고친 뒤 이걸 치면 주기 재확인
# (AKG_REFRESH_SECONDS, 기본 300초)을 기다리지 않고 즉시 반영된다.
#
# 실행: ./scripts/reload.sh [BASE_URL]     # 기본 http://localhost:${PORT:-8080}
#
# 리로드는 fail-open 이라 허브에 못 닿아도 서버는 멀쩡히 옛 스냅샷을 서빙한다 —
# 그래서 "안 바뀐 것"과 "못 받아온 것"을 응답의 outcome 으로만 가를 수 있다.
# hub-unreachable 이면 exit 1 로 떨어뜨린다: 배포 스크립트에 물렸을 때 반영이
# 안 된 채로 조용히 지나가면 안 되니까.
set -u

BASE=${1:-http://localhost:${PORT:-8080}}

TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT

CODE=$(curl -s -o "$TMP" -w '%{http_code}' -X POST "$BASE/admin/reload")
if [ "$CODE" != "200" ]; then
	echo "리로드 실패 — HTTP $CODE ($BASE/admin/reload)"
	[ -s "$TMP" ] && cat "$TMP" && echo
	exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
	cat "$TMP"
	echo
	echo "(jq 가 없어 원문만 보여준다 — outcome 이 fetched 인지 직접 확인해라)"
	exit 0
fi

jq -r 'to_entries[] | "\(.key): source=\(.value.source) outcome=\(.value.outcome) count=\(.value.count)"' "$TMP"

if jq -e 'any(.[]; .outcome == "not-configured")' "$TMP" >/dev/null; then
	echo "(akg 미구성 — AKG_URL 이 없거나 restricted 라 리로드할 원본이 없다)"
fi
if jq -e 'any(.[]; .outcome == "already-refreshing")' "$TMP" >/dev/null; then
	echo "(주기 refresh 가 이미 돌고 있어 건너뛰었다 — 그쪽이 최신을 가져온다)"
fi
if jq -e 'any(.[]; .outcome == "hub-unreachable")' "$TMP" >/dev/null; then
	echo "허브에 닿지 못했다 — 위 count 는 갱신 전 스냅샷이다. 서버 로그의 'akg 허브' warn 을 봐라."
	exit 1
fi
