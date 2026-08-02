package fdc.agent.api;

import fdc.agent.akg.AkgSource;
import fdc.agent.akg.AkgSource.Reload;
import fdc.agent.lines.LineSource;
import fdc.agent.skills.SkillSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /admin/reload — akg 소스(스킬·라인) 강제 리로드(#40). 운영(ops) 표면이라
 * 제품 문법({@code /api/fdc/v1/*}) 밖, {@code /health} 와 같은 층이다.
 *
 * <p>주기 refresh(AKG_REFRESH_SECONDS, 기본 300초)가 반영 상한이던 것을 즉시로 —
 * 허브에 스킬을 올린 운영자가 5분을 기다리는 대신 이 한 번으로 반영하고, 응답으로
 * 확인까지 끝낸다.
 *
 * <p><b>확인이 되려면 결과가 정직해야 한다.</b> 리로드는 fail-open 이라 허브에 못
 * 닿아도 예외 없이 직전 스냅샷을 계속 서빙한다. 그래서 "시도했다"만 보고하면 허브가
 * 죽은 것과 바뀐 게 없는 것이 같은 응답이 되고, 운영자는 반영 실패를 성공으로 읽는다.
 * 무슨 일이 있었는지는 {@code outcome} 이 구분해서 말한다({@link Reload}).
 * akg 미구성이면 404 가 아니라 {@code not-configured} 로 보고한다 — 운영 스크립트가
 * 분기 없이 상태를 읽게.
 */
@RestController
public class AdminController {

    private final SkillSource skillSource;
    private final LineSource lineSource;

    public AdminController(SkillSource skillSource, LineSource lineSource) {
        this.skillSource = skillSource;
        this.lineSource = lineSource;
    }

    @PostMapping("/admin/reload")
    public Map<String, Object> reload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("skills", section(skillSource, "bundle", () -> skillSource.specs().size()));
        out.put("lines", section(lineSource, "none", () -> lineSource.codes().size()));
        return out;
    }

    /**
     * 리로드를 먼저 돌리고 그다음에 개수를 센다 — 순서가 뒤집히면 갱신 <i>전</i>
     * 개수를 보고하게 되고, 그러면 반영 확인이라는 목적 자체가 무너진다.
     */
    private static Map<String, Object> section(
            Object source, String fallbackLabel, IntSupplier count) {
        String label = fallbackLabel;
        Reload outcome = Reload.NOT_CONFIGURED;
        if (source instanceof AkgSource akg) {
            label = "akg";
            outcome = akg.reloadNow();
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("source", label);
        s.put("outcome", outcome.wire());
        s.put("count", count.getAsInt());
        return s;
    }
}
