package cn.citprobe.bilibot.auth;

public record SessionState(String cookie, long savedAt) {
}
