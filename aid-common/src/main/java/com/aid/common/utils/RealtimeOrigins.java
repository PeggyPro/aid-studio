package com.aid.common.utils;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Exact browser origins; never interprets wildcards as trusted websites. */
public final class RealtimeOrigins {
    private RealtimeOrigins() { }

    public static Set<String> parse(String value) {
        Set<String> origins = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return origins;
        if (value.length() > 16384) throw new IllegalArgumentException("允许连接的网站地址过长");
        for (String entry : value.split("[,\\r\\n]+")) {
            if (entry.isBlank()) continue;
            origins.add(normalize(entry.trim()));
            if (origins.size() > 100) throw new IllegalArgumentException("允许连接的网站最多配置100个");
        }
        return origins;
    }

    public static String normalize(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || uri.getPort() > 65535 || uri.getPort() == 0
                    || value.contains("*") || value.contains("\\")) throw new IllegalArgumentException();
            int port = uri.getPort();
            if (port == (scheme.equals("https") ? 443 : 80)) port = -1;
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (port < 0 ? "" : ":" + port);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("请填写完整的网站来源（协议、域名及可选端口），不要包含路径、账号、通配符或查询参数");
        }
    }
}
