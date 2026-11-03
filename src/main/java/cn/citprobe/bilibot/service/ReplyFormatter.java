package cn.citprobe.bilibot.service;

import cn.citprobe.bilibot.config.AppConfig;

import java.util.Locale;

/**
 * 把模型输出整理成适合 B 站评论区的纯文本，并保证不超过评论长度限制。
 */
public final class ReplyFormatter {

    private final AppConfig.Reply config;

    public ReplyFormatter(AppConfig.Reply config) {
        this.config = config;
    }

    public String format(String modelOutput, String ownerName) {
        String text = modelOutput == null ? "" : modelOutput;
        text = stripMarkdown(text);
        text = sanitize(text);
        if (text.isBlank()) {
            text = config.fallbackText();
        }

        String prefix = buildPrefix(ownerName, text);
        int available = Math.max(0, config.maxLength() - prefix.length());
        if (available < 2) {
            return truncate(prefix + text, config.maxLength());
        }
        if (text.length() > available) {
            text = truncate(text, Math.max(1, available - 1)) + "…";
        }
        String result = prefix + text;
        return result.length() > config.maxLength() ? truncate(result, config.maxLength()) : result;
    }

    private String buildPrefix(String ownerName, String text) {
        StringBuilder prefix = new StringBuilder();
        String owner = sanitize(ownerName == null ? "" : ownerName).trim();
        if (config.mentionOwner() && !owner.isEmpty()) {
            String mention = "@" + owner + " ";
            if (!text.toLowerCase(Locale.ROOT).startsWith(mention.toLowerCase(Locale.ROOT))) {
                prefix.append(mention);
            }
        }
        if (config.prefix() != null && !config.prefix().isBlank()) {
            prefix.append(config.prefix().trim()).append(' ');
        }
        return prefix.toString();
    }

    static String stripMarkdown(String input) {
        String text = input.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replaceAll("(?s)```[a-zA-Z0-9_+.-]*\\s*", "");
        text = text.replace("`", "");
        text = text.replaceAll("!\\[([^\\]]*)]\\(([^)]+)\\)", "$1");
        text = text.replaceAll("\\[([^\\]]+)]\\((https?://[^)]+)\\)", "$1（$2）");
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        text = text.replaceAll("__(.+?)__", "$1");
        text = text.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        text = text.replaceAll("(?m)^\\s*>\\s?", "");
        text = text.replaceAll("(?m)^\\s*[-*+]\\s+", "・ ");
        text = text.replaceAll("(?m)^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$", "");
        text = text.replaceAll("<[^>]{1,120}>", "");
        return text;
    }

    private static String sanitize(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '\n' || ch == '\t') {
                sb.append(ch);
            } else if (!Character.isISOControl(ch)) {
                sb.append(ch);
            }
        }
        String text = sb.toString()
                .replace('\t', ' ')
                .replaceAll("[ \\u00A0]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    private static String truncate(String value, int maxLength) {
        if (maxLength <= 0) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        int end = maxLength;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, Math.max(0, end)).stripTrailing();
    }
}
