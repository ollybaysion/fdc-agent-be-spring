package fdc.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 응답(2xx/4xx/5xx)에 X-Request-Id + X-Fdc-Data-Source 를 싣는다.
 * 클라이언트가 X-Request-Id 를 주면 그대로 쓰고, 없으면 생성. MDC 로 로그에도
 * 전파.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {
    private final AppProps props;

    public RequestIdFilter(AppProps props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader("x-request-id");
        String requestId = incoming != null && !incoming.isBlank()
                ? incoming
                : "req_" + UUID.randomUUID();
        response.setHeader("x-request-id", requestId);
        response.setHeader("x-fdc-data-source", props.dataSource());
        MDC.put("requestId", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
