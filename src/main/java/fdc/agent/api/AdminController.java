package fdc.agent.api;

import fdc.agent.lines.AkgLineSource;
import fdc.agent.lines.LineSource;
import fdc.agent.skills.AkgSkillSource;
import fdc.agent.skills.SkillSource;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /admin/reload — akg 소스(스킬·라인) 강제 리로드(#40). 운영(ops) 표면이라
 * 제품 문법({@code /api/fdc/v1/*}) 밖, {@code /health} 와 같은 층이다.
 *
 * <p>주기 refresh(AKG_REFRESH_SECONDS, 기본 300초)가 반영 상한이던 것을 즉시로 —
 * 허브에 스킬을 올린 운영자가 5분을 기다리는 대신 이 한 번으로 반영하고, 응답의
 * 개수로 확인까지 끝낸다. akg 미구성/제한망이면 리로드할 것이 없다 — 404 대신
 * {@code reloaded:false} 로 정직하게 보고한다(운영 스크립트가 분기 없이 상태를 읽게).
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
        out.put("skills", skillSource instanceof AkgSkillSource akg
                ? section("akg", akg.reloadNow(), skillSource.specs().size())
                : section("bundle", false, skillSource.specs().size()));
        out.put("lines", lineSource instanceof AkgLineSource akg
                ? section("akg", akg.reloadNow(), lineSource.codes().size())
                : section("none", false, lineSource.codes().size()));
        return out;
    }

    private static Map<String, Object> section(String source, boolean reloaded, int count) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("source", source);
        s.put("reloaded", reloaded);
        s.put("count", count);
        return s;
    }
}
