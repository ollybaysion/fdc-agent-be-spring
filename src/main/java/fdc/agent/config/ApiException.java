package fdc.agent.config;

/**
 * HTTP 상태를 갖는 에러(Node 판의 `Object.assign(new Error(...), {statusCode})`
 * 대응). error 코드는 API.md §에러 형식의 머신 판독 코드.
 */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
