package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import fdc.agent.akg.AkgSource.Reload;
import fdc.agent.schema.AkgSchemaSource;
import fdc.agent.schema.SchemaDoc;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * akg 허브 런타임 db-schema fetch(#49) — {@code AkgSkillSourceTest} 와 같은 스텁
 * 허브로 수용·격리·갱신을 검증한다. 스킬과 다른 지점은 <b>번들 폴백이 없다</b>는
 * 것 — 허브에 못 닿으면 빈 값이지 classpath 대체본이 아니다.
 */
class AkgSchemaSourceTest {

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
                    e.put("type", "db-schema").put("id", id)
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
                envelope.put("schema", "db-schema/v1").put("id", id)
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

    /** {@code table}/{@code purpose.text}/컬럼 하나(mark)를 담은 최소 db-schema 문서. */
    private ObjectNode putSchema(String id, String table, String mark, String rev, String status) {
        ObjectNode body = JSON.createObjectNode();
        body.put("table", table);
        ObjectNode purpose = JSON.createObjectNode();
        purpose.put("text", table + " 설명").put("tier", "inferred");
        body.set("purpose", purpose);
        ObjectNode columnDescs = JSON.createObjectNode();
        ObjectNode col = JSON.createObjectNode();
        col.put("text", mark).put("tier", "inferred");
        columnDescs.set("SNSR_TYPE_CD", col);
        body.set("columnDescs", columnDescs);
        bodies.put(id, body);
        revs.put(id, rev);
        statuses.put(id, status);
        return body;
    }

    @Test
    void 허브에서_컬럼_의미를_받아온다() throws Exception {
        putSchema("fdc_sensor", "FDC_SENSOR", "센서 종류 코드", "r1", "active");
        AkgSchemaSource source = new AkgSchemaSource(startStub(), null, 300);

        Optional<SchemaDoc> doc = source.byTable("FDC_SENSOR");
        assertThat(doc).isPresent();
        assertThat(doc.get().table()).isEqualTo("FDC_SENSOR");
        assertThat(doc.get().tableComment()).isEqualTo("FDC_SENSOR 설명");
        assertThat(doc.get().columnDescs()).containsEntry("SNSR_TYPE_CD", "센서 종류 코드");
        // 토큰 미설정 → Authorization 헤더 자체가 없다("Bearer null" 금지).
        assertThat(lastAuth).isNull();
    }

    @Test
    void 조회는_대소문자를_가리지_않는다() throws Exception {
        putSchema("fdc_sensor", "FDC_SENSOR", "센서 종류 코드", "r1", "active");
        AkgSchemaSource source = new AkgSchemaSource(startStub(), null, 300);

        assertThat(source.byTable("fdc_sensor")).isPresent();
        assertThat(source.byTable("Fdc_Sensor")).isPresent();
    }

    @Test
    void 허브_불가침이면_빈_값이다_번들_폴백이_없다() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        AkgSchemaSource source = new AkgSchemaSource("http://127.0.0.1:" + deadPort, null, 300);
        assertThat(source.byTable("FDC_SENSOR")).isEmpty();
        assertThat(source.size()).isZero();
    }

    @Test
    void deprecated_티어_컬럼은_제외된다() throws Exception {
        ObjectNode body = putSchema("fdc_sensor", "FDC_SENSOR", "센서 종류 코드", "r1", "active");
        ObjectNode deprecatedCol = JSON.createObjectNode();
        deprecatedCol.put("text", "옛 설명").put("tier", "deprecated");
        ((ObjectNode) body.path("columnDescs")).set("OLD_COL", deprecatedCol);

        AkgSchemaSource source = new AkgSchemaSource(startStub(), null, 300);
        Optional<SchemaDoc> doc = source.byTable("FDC_SENSOR");
        assertThat(doc).isPresent();
        assertThat(doc.get().columnDescs()).containsKey("SNSR_TYPE_CD");
        assertThat(doc.get().columnDescs()).doesNotContainKey("OLD_COL");
    }

    @Test
    void inactive_문서는_수용하지_않는다() throws Exception {
        putSchema("fdc_sensor", "FDC_SENSOR", "센서 종류 코드", "r1", "active");
        putSchema("fdc_draft", "FDC_DRAFT", "적재 중", "r1", "inactive");

        AkgSchemaSource source = new AkgSchemaSource(startStub(), null, 300);
        assertThat(source.byTable("FDC_SENSOR")).isPresent();
        assertThat(source.byTable("FDC_DRAFT")).isEmpty();
        assertThat(source.size()).isEqualTo(1);
    }

    @Test
    void table이_없는_문서는_그_문서만_제외되고_나머지는_산다() throws Exception {
        ObjectNode bad = putSchema("bad", "BAD", "mark", "r1", "active");
        bad.remove("table");
        putSchema("fdc_sensor", "FDC_SENSOR", "센서 종류 코드", "r1", "active");

        AkgSchemaSource source = new AkgSchemaSource(startStub(), null, 300);
        assertThat(source.byTable("FDC_SENSOR")).isPresent();
        assertThat(source.size()).isEqualTo(1);
    }

    @Test
    void rev가_같으면_재fetch_생략_바뀌면_반영된다() throws Exception {
        putSchema("fdc_sensor", "FDC_SENSOR", "초판 설명", "r1", "active");
        // refreshSeconds=0 → 매 byTable() 호출이 목록을 재확인(테스트용).
        AkgSchemaSource source = new AkgSchemaSource(startStub(), null, 0);
        assertThat(docFetches.get()).isEqualTo(1); // 기동 시 1회

        source.byTable("FDC_SENSOR"); // rev 그대로 → 목록만 확인, 문서 재fetch 없음
        assertThat(docFetches.get()).isEqualTo(1);

        putSchema("fdc_sensor", "FDC_SENSOR", "개정판 설명", "r2", "active");
        Optional<SchemaDoc> doc = source.byTable("FDC_SENSOR"); // rev 변경 → 재fetch·반영
        assertThat(docFetches.get()).isEqualTo(2);
        assertThat(doc.get().columnDescs()).containsEntry("SNSR_TYPE_CD", "개정판 설명");
    }

    @Test
    void 토큰은_Bearer_헤더로_간다() throws Exception {
        putSchema("fdc_sensor", "FDC_SENSOR", "센서 종류 코드", "r1", "active");
        new AkgSchemaSource(startStub(), "sekrit", 300);
        assertThat(lastAuth).isEqualTo("Bearer sekrit");
    }

    @Test
    void reloadNow는_주기와_무관하게_즉시_반영한다() throws Exception {
        putSchema("fdc_sensor", "FDC_SENSOR", "초판 설명", "r1", "active");
        // refreshSeconds=300 — 주기 타이머로는 이 테스트 동안 재확인이 일어나지 않는다.
        AkgSchemaSource source = new AkgSchemaSource(startStub(), null, 300);
        assertThat(docFetches.get()).isEqualTo(1); // 기동 시 1회

        putSchema("fdc_sensor", "FDC_SENSOR", "개정판 설명", "r2", "active");
        source.byTable("FDC_SENSOR"); // 주기 미도래 → 구판 그대로, 재확인 없음
        assertThat(docFetches.get()).isEqualTo(1);

        assertThat(source.reloadNow()).isEqualTo(Reload.FETCHED); // 강제 → 즉시 재확인·재fetch
        assertThat(docFetches.get()).isEqualTo(2);
        assertThat(source.byTable("FDC_SENSOR").get().columnDescs())
                .containsEntry("SNSR_TYPE_CD", "개정판 설명");
    }

    /**
     * fail-open 이라 허브가 죽어도 예외가 안 나고 마지막 스냅샷이 그대로 산다 — 그래서
     * 리로드가 실패했다는 사실은 반환값 말고는 알 길이 없다(#40).
     */
    @Test
    void 허브에_못_닿으면_hub_unreachable을_돌려주고_스냅샷을_유지한다() throws Exception {
        putSchema("fdc_sensor", "FDC_SENSOR", "초판 설명", "r1", "active");
        AkgSchemaSource source = new AkgSchemaSource(startStub(), null, 300);
        source.byTable("FDC_SENSOR");

        server.stop(0);
        server = null;

        assertThat(source.reloadNow()).isEqualTo(Reload.HUB_UNREACHABLE);
        assertThat(source.byTable("FDC_SENSOR")).isPresent();
        assertThat(source.byTable("FDC_SENSOR").get().columnDescs())
                .containsEntry("SNSR_TYPE_CD", "초판 설명");
    }
}
