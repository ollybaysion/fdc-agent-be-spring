package fdc.agent.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 통일된 에러 형식(API.md §에러 형식, Node 판 app.ts setErrorHandler 대응).
 * production 에서는 5xx 내부 상세를 비노출하고 로그에만 남긴다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final AppProps props;

    public ApiExceptionHandler(AppProps props) {
        this.props = props;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> invalidJson(HttpMessageNotReadableException e) {
        return ResponseEntity.status(400).body(Map.of("error", "invalid_json"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", "not_found"));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        log.error("unhandled error", e);
        int status = e.status() >= 400 ? e.status() : 500;
        String code = status == 500 ? "internal" : e.code();
        String message = props.isProd() && status == 500 ? "internal error" : e.getMessage();
        return ResponseEntity.status(status).body(body(code, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception e) {
        log.error("unhandled error", e);
        String message = props.isProd() ? "internal error" : e.getMessage();
        return ResponseEntity.status(500).body(body("internal", message));
    }

    private static Map<String, Object> body(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        if (message != null) {
            body.put("message", message);
        }
        return body;
    }
}
