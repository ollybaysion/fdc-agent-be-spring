package fdc.agent.api;

import fdc.agent.contract.Compare;
import fdc.agent.contract.EquipmentDetail;
import fdc.agent.data.EquipmentRepo;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정형 4개 GET. demo-fe 의 mock route 와
 * 동일한 경로/에러코드/status 를 그대로 미러링하여, BACKEND_URL forward 시
 * 프론트가 차이를 못 느끼게 한다.
 */
@RestController
public class EquipmentController {

    private final EquipmentRepo repo;

    public EquipmentController(EquipmentRepo repo) {
        this.repo = repo;
    }

    @GetMapping("/api/fdc/v1/equipment/{id}")
    public ResponseEntity<Object> detail(@PathVariable String id) {
        EquipmentDetail detail = repo.getDetail(id);
        if (detail == null) {
            return notFound();
        }
        return ResponseEntity.ok(detail);
    }

    @GetMapping("/api/fdc/v1/equipment/{id}/peers")
    public ResponseEntity<Object> peers(@PathVariable String id) {
        if (repo.getDetail(id) == null) {
            return notFound();
        }
        return ResponseEntity.ok(repo.getPeers(id));
    }

    @GetMapping("/api/fdc/v1/equipment/{id}/setup-events")
    public ResponseEntity<Object> setupEvents(@PathVariable String id) {
        if (repo.getDetail(id) == null) {
            return notFound();
        }
        return ResponseEntity.ok(repo.getSetupEvents(id));
    }

    @GetMapping("/api/fdc/v1/equipment/{id}/compare")
    public ResponseEntity<Object> compare(
            @PathVariable String id,
            @RequestParam(required = false) String peerId,
            @RequestParam(required = false) String recipe,
            @RequestParam(name = "window", required = false) String window) {
        String recipeVal = recipe != null ? recipe : Compare.RECIPES.get(0);
        double windowDays = jsNumber(window != null ? window : "7");

        if (repo.getDetail(id) == null) {
            return notFound();
        }
        if (peerId == null || repo.getDetail(peerId) == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "peer_not_found");
            if (peerId != null) {
                body.put("peerId", peerId);
            }
            return ResponseEntity.badRequest().body(body);
        }
        if (!Compare.RECIPES.contains(recipeVal)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "unknown_recipe");
            body.put("recipe", recipeVal);
            body.put("allowed", Compare.RECIPES);
            return ResponseEntity.badRequest().body(body);
        }
        if (!isAllowedWindow(windowDays)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "unknown_window");
            // JS 판은 Number("bad")=NaN 을 JSON null 로 직렬화 — 동일하게.
            body.put("window", Double.isNaN(windowDays) ? null : fdc.agent.util.Js.num(windowDays));
            body.put("allowed", Compare.WINDOWS);
            return ResponseEntity.badRequest().body(body);
        }

        return ResponseEntity.ok(repo.getCompare(id, peerId, recipeVal, (int) windowDays));
    }

    private static ResponseEntity<Object> notFound() {
        return ResponseEntity.status(404).body(Map.of("error", "not_found"));
    }

    private static boolean isAllowedWindow(double windowDays) {
        return Compare.WINDOWS.stream().anyMatch(w -> w.doubleValue() == windowDays);
    }

    /** JS `Number()` 의미론: 빈 문자열 → 0, 숫자 아님 → NaN. */
    private static double jsNumber(String s) {
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
