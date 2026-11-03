package cn.citprobe.bilibot.auth;

public final class BilibiliAuthException extends RuntimeException {

    private final int code;

    public BilibiliAuthException(String message) {
        this(message, 0, null);
    }

    public BilibiliAuthException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public BilibiliAuthException(String message, int code) {
        this(message, code, null);
    }

    public BilibiliAuthException(String message, int code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
