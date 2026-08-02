package fdc.agent.config;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 통일된 에러 형식(API.md §에러 형식).
 * production 에서는 5xx 내부 상세를 비노출하고 로그에만 남긴다.
 *
 * <p><b>{@link ResponseEntityExceptionHandler} 를 상속하는 이유</b>(#45) — 이 advice 는
 * 컨트롤러 밖에서 나는 Spring MVC 프레임워크 예외(메서드 불일치·미지원 Content-Type 등)
 * 보다 <b>먼저</b> 돈다. 그래서 포괄 {@code Exception} 핸들러만 있으면 그것들이 전부
 * 여기로 빨려 들어와 500 이 됐다 — 405·415 여야 할 <b>호출자 잘못이 서버 고장으로</b>
 * 보고되고, {@code Allow} 헤더도 사라지고, 로그에는 ERROR 스택트레이스가 쌓였다.
 * 부모가 그 예외들에 정확한 상태코드를 매핑해 두었으므로, 상속하면 포괄 핸들러는
 * 진짜 미처리 예외만 받는다(가장 구체적인 핸들러가 이긴다).
 *
 * <p>대신 부모의 기본 본문은 Spring 6 의 {@code ProblemDetail} 이라 우리 계약과 다르다 —
 * {@link #handleExceptionInternal} 이 모든 프레임워크 응답을 {@code {error, message?}} 로
 * 되돌린다. 헤더는 부모가 만든 것을 그대로 넘긴다(405 의 {@code Allow} 가 여기 실려 있다).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final AppProps props;

    public ApiExceptionHandler(AppProps props) {
        this.props = props;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        int status = e.status() >= 400 ? e.status() : 500;
        logAt(status, e);
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

    /**
     * 부모가 상태코드·헤더까지 정해 넘겨준 프레임워크 예외를 우리 본문 형식으로 싣는다.
     * {@code body} 인자(ProblemDetail)는 의도적으로 버린다 — 형식 진실원은 API.md 다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // SSE 처럼 이미 응답이 나가기 시작했으면 상태코드를 바꿀 수 없다(부모와 같은 규율).
        if (request instanceof ServletWebRequest servletRequest) {
            HttpServletResponse response = servletRequest.getResponse();
            if (response != null && response.isCommitted()) {
                log.warn("응답이 이미 커밋됨 — 무시: {}", ex.toString());
                return null;
            }
        }
        logAt(status.value(), ex);
        boolean maskDetail = props.isProd() && status.is5xxServerError();
        String message = maskDetail ? "internal error" : ex.getMessage();
        return new ResponseEntity<>(body(codeFor(ex, status), message), headers, status);
    }

    /**
     * 머신 판독 코드. 상태 이름을 그대로 쓰면 {@code not_found} 처럼 기존 계약과 맞는다 —
     * 어긋나는 둘만 예외로 둔다(API.md §에러 형식의 표가 진실원).
     */
    private static String codeFor(Exception ex, HttpStatusCode status) {
        if (ex instanceof HttpMessageNotReadableException) {
            return "invalid_json";
        }
        if (status.value() == 500) {
            return "internal";
        }
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "error" : resolved.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 4xx 는 호출자 잘못이라 스택트레이스를 남길 이유가 없다 — 그렇게 하면 오타 하나가
     * ERROR 로그가 되어 진짜 5xx 를 잡음에 묻는다.
     */
    private static void logAt(int status, Exception e) {
        if (status >= 500) {
            log.error("unhandled error", e);
        } else if (log.isDebugEnabled()) {
            log.debug("client error {} — {}", status, e.toString());
        }
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
