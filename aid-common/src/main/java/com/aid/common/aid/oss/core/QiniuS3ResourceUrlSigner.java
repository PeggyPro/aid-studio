package com.aid.common.aid.oss.core;

import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner.AuthLocation;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.utils.http.SdkHttpUtils;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/** Standard S3 query signing for the official Qiniu bucket endpoint. */
public final class QiniuS3ResourceUrlSigner {
    private static final Pattern ENDPOINT = Pattern.compile(
            "^[a-z0-9][a-z0-9.-]*\\.s3\\.([a-z0-9-]+)\\.qiniucs\\.com$", Pattern.CASE_INSENSITIVE);
    private static final AwsV4HttpSigner SIGNER = AwsV4HttpSigner.create();

    private QiniuS3ResourceUrlSigner() { }

    /** Returns null for an ordinary Kodo download domain; the caller uses the native Kodo signer. */
    public static String signIfSupported(String domain, String objectKey, String accessKey,
                                         String secretKey, int expirySeconds) {
        if (domain == null || domain.isBlank()) return null;
        URI base = URI.create(domain);
        var match = ENDPOINT.matcher(base.getHost() == null ? "" : base.getHost());
        if (!match.matches()) return null;
        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || "http".equals(scheme)) || base.getUserInfo() != null
                || base.getRawQuery() != null || base.getFragment() != null
                || !(base.getRawPath() == null || base.getRawPath().isEmpty() || "/".equals(base.getRawPath()))
                || (base.getPort() != -1 && base.getPort() != ("https".equals(scheme) ? 443 : 80))) {
            throw new IllegalArgumentException("Invalid Qiniu S3 resource domain");
        }
        if (objectKey == null || objectKey.isBlank() || accessKey == null || accessKey.isBlank()
                || secretKey == null || secretKey.isBlank() || expirySeconds < 1 || expirySeconds > 604800) {
            throw new IllegalArgumentException("Invalid S3 signing arguments");
        }
        SdkHttpRequest request = SdkHttpRequest.builder().uri(base).method(SdkHttpMethod.GET)
                .encodedPath("/" + SdkHttpUtils.urlEncodeIgnoreSlashes(objectKey)).build();
        var signed = SIGNER.sign(r -> r.identity(AwsCredentialsIdentity.create(accessKey, secretKey))
                .request(request).putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "s3")
                .putProperty(AwsV4HttpSigner.REGION_NAME, match.group(1).toLowerCase(Locale.ROOT))
                .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AuthLocation.QUERY_STRING)
                .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, Duration.ofSeconds(expirySeconds))
                .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, false)
                .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false)
                .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, false));
        return signed.request().getUri().toASCIIString();
    }
}
