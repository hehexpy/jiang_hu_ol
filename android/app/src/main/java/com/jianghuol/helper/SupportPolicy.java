package com.jianghuol.helper;

import java.net.URI;
import java.util.Collection;
import java.util.regex.Pattern;

/** 可离线验证的传输与脱敏规则。 */
final class SupportPolicy {
    static final int REPORT_LIMIT = 4 * 1024 * 1024;
    static final long APK_LIMIT = 160L * 1024 * 1024;
    private static final Pattern CREDENTIAL = Pattern.compile(
        "(?i)([\\\"']?(?:password|passwd|pwd|token|authorization|secret|密码|口令)[\\\"']?\\s*[:=：]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}]+)");
    static String redact(String text, Collection<String> secrets) {
        String out = text;
        for (String secret : secrets) if (secret != null && !secret.isEmpty()) {
            out = out.replace(secret.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"), "[已脱敏]");
            out = out.replace(secret, "[已脱敏]");
        }
        out = out.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer [已脱敏]");
        return CREDENTIAL.matcher(out).replaceAll("$1[已脱敏]");
    }
    static URI https(String value) {
        URI uri;
        try { uri = URI.create(value); } catch (Exception e) { throw new IllegalArgumentException("地址格式错误"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("请填写不含凭据的 HTTPS 地址");
        return uri;
    }
    static String repo(String value) {
        String result=value.trim();
        if (!result.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || result.contains(".."))
            throw new IllegalArgumentException("GitHub 仓库格式应为 owner/repo");
        return result;
    }
    static boolean githubHost(String host) {
        return "github.com".equalsIgnoreCase(host) || "api.github.com".equalsIgnoreCase(host)
            || "objects.githubusercontent.com".equalsIgnoreCase(host)
            || "release-assets.githubusercontent.com".equalsIgnoreCase(host);
    }
    static boolean reportEntry(String name) {
        return name.equals("report.json") || name.equals("logs/app.log") || name.equals("logs/memory.log")
            || name.matches("farm/[A-Za-z0-9_-]+/(state\\.json|timing\\.jsonl|actions\\.jsonl)");
    }
}
