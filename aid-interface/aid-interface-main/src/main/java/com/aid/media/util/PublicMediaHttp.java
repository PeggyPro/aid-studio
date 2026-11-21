package com.aid.media.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

/**
 * 服务端读取不可信公网素材的 HTTP 边界。
 * @author 视觉AID
 */
public final class PublicMediaHttp {
    private static final int MAX_REDIRECTS = 4;
    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final CloseableHttpClient CLIENT = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build())
                    // 连接器直接使用同一次校验过的 DNS 结果，保留原主机的 TLS 验证。
                    .setDnsResolver(new DnsResolver() {
                        @Override
                        public InetAddress[] resolve(String host) throws UnknownHostException {
                            return publicAddresses(host);
                        }

                        @Override
                        public String resolveCanonicalHostname(String host) {
                            return host;
                        }
                    }).build())
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .setUserAgent("aid-server")
            .build();

    private PublicMediaHttp() {
    }

    public static void validateDestination(String source) throws IOException {
        publicAddresses(parse(source).getHost());
    }

    static <T> T get(String source, int timeoutMs, HttpClientResponseHandler<T> reader) throws IOException {
        URI target = parse(source);
        Timeout timeout = Timeout.ofMilliseconds(timeoutMs);
        RequestConfig config = RequestConfig.custom()
                .setConnectionRequestTimeout(timeout).setResponseTimeout(timeout).build();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpGet request = new HttpGet(target);
            request.setConfig(config);
            Step<T> step;
            CloseableHttpResponse response = CloseableHttpResponse.adapt(CLIENT.executeOpen(null, request, null));
            try {
                step = read(response, reader);
            } catch (org.apache.hc.core5.http.HttpException ex) {
                throw new IOException("素材响应无效", ex);
            } finally {
                // 截断或跳转时直接释放连接，避免继续排空未读取的正文。
                response.close(CloseMode.IMMEDIATE);
            }
            if (step.location() == null) {
                return step.result();
            }
            target = parse(target.resolve(step.location()).toString());
        }
        throw new IOException("素材跳转过多");
    }

    private static <T> Step<T> read(ClassicHttpResponse response, HttpClientResponseHandler<T> reader)
            throws IOException, org.apache.hc.core5.http.HttpException {
        int code = response.getCode();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            Header location = response.getFirstHeader(HttpHeaders.LOCATION);
            if (location == null || location.getValue().isBlank()) {
                throw new IOException("素材跳转无效");
            }
            return new Step<>(location.getValue(), null);
        }
        return new Step<>(null, reader.handleResponse(response));
    }

    private static URI parse(String source) throws IOException {
        try {
            URI uri = URI.create(source);
            String scheme = Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                throw new IOException("素材地址无效");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new IOException("素材地址无效");
        }
    }

    private static InetAddress[] publicAddresses(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new UnknownHostException("素材地址不可访问");
            }
        }
        return addresses;
    }

    static boolean isPublicAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        if (bytes.length == 4) {
            return first != 0 && first != 10 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && (second == 168 || second == 0 && bytes[2] == 0))
                    && !(first == 198 && (second == 18 || second == 19));
        }
        // IPv6 仅允许全球单播，排除 6to4、Teredo 和特殊用途前缀。
        return (first & 224) == 32
                && !(first == 32 && second == 2)
                && !(first == 32 && second == 1 && (bytes[2] & 254) == 0);
    }

    private record Step<T>(String location, T result) {
    }
}
