package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import fdc.agent.skills.AkgSkillSource;
import fdc.agent.skills.SkillLoader;
import fdc.agent.skills.SkillRegistry;
import fdc.agent.skills.SkillSpec;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * akg 허브 런타임 스킬 fetch(#8) — 스텁 허브로 수용·폴백·격리·갱신을 검증.
 * 계약: URL(+토큰)만 주면 추가 작업 없이 스킬이 살아야 하고, 허브·나쁜 문서가
 * 챗을 죽여서는 안 된다.
 */
class AkgSkillSourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;

    // 스텁 상태 — 각 테스트가 직접 채우고 갈아끼운다.
    private final Map<String, ObjectNode> bodies = new LinkedHashMap<>();
    private final Map<String, String> revs = new LinkedHashMap<>();
    private final Map<String, String> statuses = new LinkedHashMap<>();
    private final AtomicInteger docFetches = new AtomicInteger();
    private volatile String lastAuth = "unset";

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/docs", ex -> {
            lastAuth = ex.getRequestHeaders().getFirst("Authorization");
            String path = ex.getRequestURI().getPath();
            byte[] out;
            if (path.equals("/api/docs")) {
                ArrayNode arr = JSON.createArrayNode();
                for (String id : bodies.keySet()) {
                    ObjectNode e = JSON.createObjectNode();
                    e.put("type", "domain-skill").put("id", id)
                            .put("status", statuses.get(id)).put("rev", revs.get(id));
                    arr.add(e);
                }
                ObjectNode root = JSON.createObjectNode();
                root.set("docs", arr);
                out = JSON.writeValueAsBytes(root);
            } else {
                docFetches.incrementAndGet();
                String id = path.substring(path.lastIndexOf('/') + 1);
                ObjectNode body = bodies.get(id);
                if (body == null) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                ObjectNode envelope = JSON.createObjectNode();
                envelope.put("schema", "domain-skill/v1").put("id", id)
                        .put("status", statuses.get(id));
                envelope.set("body", body);
                ObjectNode root = JSON.createObjectNode();
                root.set("json", envelope);
                root.put("rev", revs.get(id));
                out = JSON.writeValueAsBytes(root);
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 번들 explain-sensor spec 을 허브판으로 변형해 적재 — focus 로 출처를 구분한다. */
    private ObjectNode putSkill(String id, String focus, String rev, String status) throws Exception {
        ObjectNode body;
        try (var in = getClass().getClassLoader()
                .getResourceAsStream("skills/fdc-explain-sensor.spec.json")) {
            body = (ObjectNode) JSON.readTree(in);
        }
        body.put("name", id).put("focus", focus);
        bodies.put(id, body);
        revs.put(id, rev);
        statuses.put(id, status);
        return body;
    }

    @Test
    void 허브에_닿으면_허브가_진실원이다_번들과_합치지_않는다() throws Exception {
        putSkill("fdc-explain-sensor", "허브판 상태", "r1", "active");
        AkgSkillSource source = new AkgSkillSource(startStub(), null, 300);

        List<SkillSpec> specs = source.specs();
        // 번들엔 스킬이 2개지만 허브가 진실원 — 허브의 1개만 산다(삭제 부활 방지).
        assertThat(specs).hasSize(1);
        assertThat(SkillLoader.synthesizeDescription(specs.get(0))).contains("허브판 상태");
        // 토큰 미설정 → Authorization 헤더 자체가 없다("Bearer null" 금지).
        assertThat(lastAuth).isNull();
    }

    @Test
    void 허브_불가침이면_classpath_번들_폴백() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        AkgSkillSource source = new AkgSkillSource("http://127.0.0.1:" + deadPort, null, 300);
        assertThat(source.specs()).isEqualTo(SkillRegistry.bundledSpecs());
    }

    @Test
    void 나쁜_spec은_그_스킬만_제외되고_나머지는_산다() throws Exception {
        putSkill("good-skill", "정상 상태", "r1", "active");
        ObjectNode bad = putSkill("bad-skill", "고장 상태", "r1", "active");
        // 배선 부정합 — binds 가 inputs 에 없는 인자를 참조(validateBinds 거부).
        ((ObjectNode) bad.path("steps").get(0).path("binds").path("id")).put("arg", "nope");

        AkgSkillSource source = new AkgSkillSource(startStub(), null, 300);
        List<SkillSpec> specs = source.specs();
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).name()).isEqualTo("good-skill");
    }

    @Test
    void inactive_문서는_수용하지_않는다() throws Exception {
        putSkill("live-skill", "정상 상태", "r1", "active");
        putSkill("draft-skill", "적재 중", "r1", "inactive");

        AkgSkillSource source = new AkgSkillSource(startStub(), null, 300);
        List<SkillSpec> specs = source.specs();
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).name()).isEqualTo("live-skill");
    }

    @Test
    void rev가_같으면_재fetch_생략_바뀌면_반영된다() throws Exception {
        putSkill("fdc-explain-sensor", "초판 상태", "r1", "active");
        // refreshSeconds=0 → 매 specs() 호출이 목록을 재확인(테스트용).
        AkgSkillSource source = new AkgSkillSource(startStub(), null, 0);
        assertThat(docFetches.get()).isEqualTo(1); // 기동 시 1회

        source.specs(); // rev 그대로 → 목록만 확인, 문서 재fetch 없음
        assertThat(docFetches.get()).isEqualTo(1);

        putSkill("fdc-explain-sensor", "개정판 상태", "r2", "active");
        List<SkillSpec> specs = source.specs(); // rev 변경 → 재fetch·반영
        assertThat(docFetches.get()).isEqualTo(2);
        assertThat(SkillLoader.synthesizeDescription(specs.get(0))).contains("개정판 상태");
    }

    @Test
    void 토큰은_Bearer_헤더로_간다() throws Exception {
        putSkill("fdc-explain-sensor", "허브판 상태", "r1", "active");
        new AkgSkillSource(startStub(), "sekrit", 300);
        assertThat(lastAuth).isEqualTo("Bearer sekrit");
    }

    @Test
    void reloadNow는_주기와_무관하게_즉시_반영한다() throws Exception {
        putSkill("fdc-explain-sensor", "초판 상태", "r1", "active");
        // refreshSeconds=300 — 주기 타이머로는 이 테스트 동안 재확인이 일어나지 않는다.
        AkgSkillSource source = new AkgSkillSource(startStub(), null, 300);
        assertThat(docFetches.get()).isEqualTo(1); // 기동 시 1회

        putSkill("fdc-explain-sensor", "개정판 상태", "r2", "active");
        source.specs(); // 주기 미도래 → 구판 그대로, 재확인 없음
        assertThat(docFetches.get()).isEqualTo(1);

        assertThat(source.reloadNow()).isTrue(); // 강제 → 즉시 재확인·재fetch
        assertThat(docFetches.get()).isEqualTo(2);
        assertThat(SkillLoader.synthesizeDescription(source.specs().get(0)))
                .contains("개정판 상태");
    }
}
