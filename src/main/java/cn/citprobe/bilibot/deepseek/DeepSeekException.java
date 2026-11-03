package cn.citprobe.bilibot.deepseek;

public final class DeepSeekException extends RuntimeException {

    private final int httpStatus;
    private final boolean retryable;

    public DeepSeekException(String message, int httpStatus, boolean retryable) {
        super(message);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public DeepSeekException(String message, int httpStatus, boolean retryable, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }

    @Override
    public String toString() {
        return "DeepSeekException{httpStatus=" + httpStatus + ", retryable=" + retryable
                + ", message=" + getMessage() + '}';
    }
}
