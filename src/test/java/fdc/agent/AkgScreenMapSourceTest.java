package fdc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import fdc.agent.akg.AkgSource.Reload;
import fdc.agent.contract.ScreenMap;
import fdc.agent.screens.AkgScreenMapSource;
import fdc.agent.screens.BundledScreenMapSource;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * akg 허브 런타임 화면 카탈로그 fetch(#63) — {@code AkgSkillSource} 와 같은
 * 관용(스텁 허브로 수용·폴백·격리 검증). 계약: URL(+토큰)만 주면 추가 작업 없이
 * 화면 카탈로그가 살아야 하고, 허브·나쁜 문서가 분류를 죽여서는 안 된다.
 */
class AkgScreenMapSourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final Map<String, ObjectNode> bodies = new LinkedHashMap<>();
    private final Map<String, String> revs = new LinkedHashMap<>();
    private final Map<String, String> statuses = new LinkedHashMap<>();
    // 목록엔 있지만 envelope.body 를 null 로 응답할 id들 — treeToValue 가 예외
    // 없이 null 을 주는 경로를 재현한다(빈 객체 {} 와는 다른 결함 형태).
    private final Set<String> emptyBodyIds = new LinkedHashSet<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/docs", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] out;
            if (path.equals("/api/docs")) {
                ArrayNode arr = JSON.createArrayNode();
                for (String id : bodies.keySet()) {
                    ObjectNode e = JSON.createObjectNode();
                    e.put("type", "screen-map").put("id", id)
                            .put("status", statuses.get(id)).put("rev", revs.get(id));
                    arr.add(e);
                }
                ObjectNode root = JSON.createObjectNode();
                root.set("docs", arr);
                out = JSON.writeValueAsBytes(root);
            } else {
                String id = path.substring(path.lastIndexOf('/') + 1);
                ObjectNode body = bodies.get(id);
                if (body == null) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                ObjectNode envelope = JSON.createObjectNode();
                envelope.put("schema", "screen-map/v1").put("id", id)
                        .put("status", statuses.get(id));
                if (emptyBodyIds.contains(id)) {
                    envelope.putNull("body");
                } else {
                    envelope.set("body", body);
                }
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

    private void putScreen(String id, String name, String rev, String status) {
        ObjectNode body = JSON.createObjectNode();
        body.put("id", id).put("name", name).put("program", "FDC Monitor");
        bodies.put(id, body);
        revs.put(id, rev);
        statuses.put(id, status);
    }

    @Test
    void 허브에_닿으면_허브가_진실원이다_번들과_합치지_않는다() throws Exception {
        putScreen("hub-screen", "허브판 화면", "r1", "active");
        AkgScreenMapSource source = new AkgScreenMapSource(startStub(), null, 300);

        List<ScreenMap> maps = source.maps();
        // 번들엔 데모 화면이 3개지만 허브가 진실원 — 허브의 1개만 산다(삭제 부활 방지).
        assertThat(maps).hasSize(1);
        assertThat(maps.get(0).name()).isEqualTo("허브판 화면");
    }

    @Test
    void 허브_불가침이면_classpath_번들_폴백() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        AkgScreenMapSource source = new AkgScreenMapSource("http://127.0.0.1:" + deadPort, null, 300);
        assertThat(source.maps()).isEqualTo(new BundledScreenMapSource().maps());
    }

    @Test
    void 나쁜_문서는_그_문서만_제외되고_나머지는_산다() throws Exception {
        putScreen("good-screen", "정상 화면", "r1", "active");
        ObjectNode bad = JSON.createObjectNode();
        bad.put("program", "미상"); // id·name 없음 — ScreenMap 컴팩트 생성자가 거부
        bodies.put("bad-screen", bad);
        revs.put("bad-screen", "r1");
        statuses.put("bad-screen", "active");

        AkgScreenMapSource source = new AkgScreenMapSource(startStub(), null, 300);
        List<ScreenMap> maps = source.maps();
        assertThat(maps).hasSize(1);
        assertThat(maps.get(0).id()).isEqualTo("good-screen");
    }

    /**
     * {@code treeToValue} 는 body 가 missing/null 이면 예외 없이 null 을 준다 —
     * {@link ScreenMap} 의 컴팩트 생성자로는 못 거르는 구멍이다(리뷰에서 발견,
     * 빈 객체 {@code {}} 와는 다른 결함 형태). 그 문서만 빠지고 나머지는 살아야 한다.
     */
    @Test
    void body가_비어있는_문서도_그_문서만_제외된다() throws Exception {
        putScreen("good-screen", "정상 화면", "r1", "active");
        putScreen("empty-body-screen", "본문 없음", "r1", "active");
        emptyBodyIds.add("empty-body-screen");

        AkgScreenMapSource source = new AkgScreenMapSource(startStub(), null, 300);
        List<ScreenMap> maps = source.maps();
        assertThat(maps).hasSize(1);
        assertThat(maps.get(0).id()).isEqualTo("good-screen");
    }

    @Test
    void inactive_문서는_수용하지_않는다() throws Exception {
        putScreen("live-screen", "정상 화면", "r1", "active");
        putScreen("draft-screen", "적재 중", "r1", "inactive");

        AkgScreenMapSource source = new AkgScreenMapSource(startStub(), null, 300);
        assertThat(source.maps()).hasSize(1);
        assertThat(source.maps().get(0).id()).isEqualTo("live-screen");
    }

    /**
     * fail-open 이라 허브가 죽어도 예외가 안 나고 카탈로그도 그대로 산다 — 그래서
     * 리로드가 실패했다는 사실은 반환값 말고는 알 길이 없다(#40 과 같은 규율).
     */
    @Test
    void 허브에_못_닿으면_hub_unreachable을_돌려준다() throws Exception {
        putScreen("hub-screen", "초판", "r1", "active");
        AkgScreenMapSource source = new AkgScreenMapSource(startStub(), null, 300);
        List<ScreenMap> before = source.maps();

        server.stop(0);
        server = null;

        assertThat(source.reloadNow()).isEqualTo(Reload.HUB_UNREACHABLE);
        // 스냅샷은 유지된다 — 리로드 실패가 분류를 죽이지 않는다.
        assertThat(source.maps()).hasSameSizeAs(before);
    }
}
