package fdc.agent.akg;

import java.util.Locale;

/**
 * akg 허브에서 런타임 fetch 하는 소스의 공통 계약 — 스킬({@code AkgSkillSource})과
 * 라인({@code AkgLineSource})이 같은 refresh 규율을 공유한다.
 *
 * <p>두 소스 모두 <b>fail-open</b> 이다: 허브에 못 닿아도 예외를 던지지 않고 직전
 * 스냅샷을 계속 서빙한다. 그래서 호출자는 무슨 일이 있었는지를 <b>반환값으로만</b>
 * 알 수 있고, 이 계약이 존재하는 이유가 그것이다 — "리로드를 시도했다"와 "새로
 * 받아왔다"를 구분하지 못하면 운영자가 반영 실패를 성공으로 읽는다.
 */
public interface AkgSource {

    /** 주기(AKG_REFRESH_SECONDS)와 무관하게 즉시 1회 재확인한다(#40). */
    Reload reloadNow();

    /** 리로드 한 번의 결과. 응답에 실리는 문자열 어휘도 여기서 관리한다. */
    enum Reload {
        /** 허브에서 목록을 받아 스냅샷을 갱신했다(내용 변경이 없었을 수도 있다). */
        FETCHED,
        /** 허브에 닿지 못했다 — 직전 스냅샷을 그대로 서빙 중이다. */
        HUB_UNREACHABLE,
        /** 다른 refresh 가 이미 돌고 있어 건너뛰었다 — 그쪽이 최신을 가져온다. */
        ALREADY_REFRESHING,
        /** akg 미구성(번들/빈 목록) — 리로드할 원본 자체가 없다. */
        NOT_CONFIGURED;

        /** 응답에 싣는 값 — 운영 스크립트가 이 문자열로 성패를 가른다. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }
}
