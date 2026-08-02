#!/bin/bash
# akg 소스(스킬·라인) 강제 리로드 — POST /admin/reload (#40).
#
# 허브에 domain-skill·fab-line 문서를 올리거나 고친 뒤 이걸 치면 주기 재확인
# (AKG_REFRESH_SECONDS, 기본 300초)을 기다리지 않고 즉시 반영된다.
#
# 실행: ./scripts/reload.sh [BASE_URL]     # 기본 http://localhost:${PORT:-8080}
#
# ⚠ reloaded=true 는 "재확인을 시도했다"는 뜻이지 "새로 받아왔다"가 아니다. 허브에
#   못 닿아도 true 로 나오고 기존 스냅샷이 그대로 유지된다(fail-open — 리로드가
#   서비스를 죽이지 않는다는 뜻이기도 하다). 실패는 서버 로그의 warn 한 줄에만
#   남으므로, 반영 여부는 아래 count 나 실제 조회로 교차 확인하는 것이 맞다.
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

if command -v jq >/dev/null 2>&1; then
	jq -r 'to_entries[] | "\(.key): source=\(.value.source) reloaded=\(.value.reloaded) count=\(.value.count)"' "$TMP"
	if jq -e 'any(.[]; .source == "bundle" or .source == "none")' "$TMP" >/dev/null; then
		echo "(akg 미구성 — AKG_URL 이 없거나 restricted 라 리로드할 원본이 없다)"
	fi
	if jq -e 'any(.[]; .source == "akg")' "$TMP" >/dev/null; then
		echo "(개수가 예상과 다르면 서버 로그에서 'akg 허브' warn 을 확인해라)"
	fi
else
	cat "$TMP"
	echo
fi
