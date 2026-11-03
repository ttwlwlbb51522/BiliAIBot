package cn.citprobe.bilibot.bilibili;

public final class BilibiliApiException extends RuntimeException {

    private final int code;
    private final int httpStatus;

    public BilibiliApiException(int code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public BilibiliApiException(int code, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean isAuthError() {
        return code == -101 || code == -102 || code == -111 || httpStatus == 401 || httpStatus == 403;
    }

    public boolean isRateLimited() {
        return code == -509 || code == -799 || httpStatus == 412 || httpStatus == 429;
    }

    public boolean isRetryable() {
        return isRateLimited()
                || code == -500 || code == -503
                || httpStatus == 500 || httpStatus == 502 || httpStatus == 503 || httpStatus == 504
                || httpStatus == 408;
    }

    @Override
    public String toString() {
        return "BilibiliApiException{code=" + code + ", httpStatus=" + httpStatus + ", message=" + getMessage() + '}';
    }
}
