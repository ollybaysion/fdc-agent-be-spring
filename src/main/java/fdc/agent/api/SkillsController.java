package fdc.agent.api;

import fdc.agent.contract.SkillCatalog;
import fdc.agent.skills.QueryPool;
import fdc.agent.skills.SkillLoader;
import fdc.agent.skills.SkillSource;
import fdc.agent.skills.SkillSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/fdc/v1/skills — 사람이 고를 스킬 목록.
 *
 * <p>출처는 {@link SkillSource} 그대로다 — 채팅이 툴로 컴파일해 쓰는 것과 <b>같은
 * spec</b> 을 편다. akg 허브가 붙어 있으면 허브 것이, 아니면 classpath 번들이 나가고,
 * 스킬이 늘어도 이 컨트롤러는 그대로다(레지스트리가 폴더를 스캔한다).
 */
@RestController
public class SkillsController {

    private final SkillSource skillSource;

    public SkillsController(SkillSource skillSource) {
        this.skillSource = skillSource;
    }

    @GetMapping("/api/fdc/v1/skills")
    public SkillCatalog skills() {
        List<SkillSpec> specs = skillSource.specs();
        QueryPool pool = QueryPool.of(specs);
        return new SkillCatalog(specs.stream().map(spec -> toEntry(spec, pool)).toList());
    }

    private static SkillCatalog.Entry toEntry(SkillSpec spec, QueryPool pool) {
        List<SkillCatalog.Input> inputs = spec.inputs().stream()
                .map(in -> new SkillCatalog.Input(
                        in.name(), in.required(), in.description(), in.type()))
                .toList();

        List<SkillCatalog.Need> needs = spec.needs().stream()
                .map(need -> new SkillCatalog.Need(need.id(), need.what(), need.when(),
                        need.fills().stream()
                                .map(f -> new SkillCatalog.Fill(f.query(), f.column()))
                                .toList()))
                .toList();

        List<SkillCatalog.Query> queries = new ArrayList<>();
        for (SkillSpec.SpecQuery query : spec.queries()) {
            Map<String, String> argBinds = new LinkedHashMap<>();
            List<String> priorQueryBinds = new ArrayList<>();
            Map<String, SkillCatalog.Bind> wiring = new LinkedHashMap<>();
            for (Map.Entry<String, SkillSpec.BindSource> e : query.bindings().entrySet()) {
                SkillSpec.BindSource src = e.getValue();
                if ("arg".equals(src.from())) {
                    argBinds.put(e.getKey(), src.arg());
                    wiring.put(e.getKey(), new SkillCatalog.Bind("arg", src.arg(), null, null));
                } else {
                    priorQueryBinds.add(e.getKey());
                    wiring.put(e.getKey(),
                            new SkillCatalog.Bind("query", null, src.query(), src.column()));
                }
            }
            QueryPool.Query pooled = pool.byId(spec.name() + "#" + query.id());
            queries.add(new SkillCatalog.Query(
                    query.id(),
                    pooled != null ? pooled.label() : query.id(),
                    pooled != null ? pooled.table() : query.table(),
                    query.sql(),
                    argBinds,
                    priorQueryBinds,
                    wiring));
        }

        return new SkillCatalog.Entry(
                // 툴 이름과 같은 규칙 — 입력 회신 inputs[skill][key] 가 여기에 맞물린다.
                spec.name().replace("-", "_"),
                spec.name(),
                SkillLoader.synthesizeDescription(spec),
                spec.questions(),
                spec.rephrasing(),
                spec.argumentHint(),
                spec.anchorTable(),
                inputs,
                needs,
                queries);
    }
}
