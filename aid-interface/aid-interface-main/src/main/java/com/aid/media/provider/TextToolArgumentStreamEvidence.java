package com.aid.media.provider;

import com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Compares upstream argument fragments with the assembled receipt using content-free fingerprints. */
final class TextToolArgumentStreamEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOOLS = 128;
    private final Map<Integer, ArgumentDigest> calls = new TreeMap<>();
    private boolean chatObserved, malformed, compared;
    private String comparison = "NOT_COMPARED";

    void accept(JsonNode frame) {
        JsonNode choices = frame == null ? null : frame.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) return;
        chatObserved = true;
        for (JsonNode call : choices.get(0).path("delta").path("tool_calls")) {
            JsonNode index = call.get("index");
            if (index == null || !index.isIntegralNumber() || !index.canConvertToInt()
                    || index.intValue() < 0 || index.intValue() >= MAX_TOOLS) { malformed = true; continue; }
            ArgumentDigest digest = calls.computeIfAbsent(index.intValue(), ignored -> new ArgumentDigest());
            JsonNode arguments = call.path("function").get("arguments");
            if (arguments == null || arguments.isNull()) continue;
            if (!arguments.isTextual()) { malformed = true; continue; }
            digest.fragments++;
            digest.characters += arguments.textValue().length();
            update(digest.upstream, arguments.textValue());
        }
    }

    void verify(TextMessageItem message) {
        if (!chatObserved) { comparison = "NOT_APPLICABLE"; return; }
        if (compared) throw mismatch();
        compared = true;
        var assembled = message == null ? null : message.getToolCalls();
        int count = assembled == null ? 0 : assembled.size();
        boolean matches = !malformed && count == calls.size();
        int position = 0;
        for (ArgumentDigest digest : calls.values()) {
            String text = assembled != null && position < count ? assembled.get(position).getArguments() : null;
            position++;
            if (text == null) { matches = false; continue; }
            MessageDigest finalDigest = sha256(); update(finalDigest, text);
            digest.assembledHash = HexFormat.of().formatHex(finalDigest.digest());
            digest.assembledCharacters = text.length();
            digest.matches = digest.characters == text.length() && fingerprint(digest.upstream).equals(digest.assembledHash);
            matches &= digest.matches;
            try {
                JsonNode value = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
                digest.validJson = value != null && value.isObject();
            } catch (Exception invalid) { digest.validJson = false; }
        }
        comparison = matches ? "MATCH" : "MISMATCH";
        if (!matches) throw mismatch();
    }

    Map<String, Object> snapshot() {
        List<Map<String, Object>> fingerprints = new ArrayList<>();
        calls.forEach((index, digest) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", index); item.put("fragments", digest.fragments);
            item.put("upstreamCharacters", digest.characters); item.put("upstreamSha256", fingerprint(digest.upstream));
            item.put("assembledCharacters", digest.assembledCharacters); item.put("assembledSha256", digest.assembledHash);
            item.put("matches", digest.matches); item.put("validJson", digest.validJson);
            fingerprints.add(item);
        });
        return Map.of("comparison", comparison, "hashEncoding", "UTF-16BE", "calls", fingerprints);
    }

    // Hash Java code units, so fragment boundaries between surrogate pairs do not change the digest.
    private static void update(MessageDigest digest, String value) {
        for (int i = 0; i < value.length(); i++) {
            char unit = value.charAt(i); digest.update((byte) (unit >>> 8)); digest.update((byte) unit);
        }
    }

    private static String fingerprint(MessageDigest digest) {
        try { return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest()); }
        catch (CloneNotSupportedException impossible) { throw new IllegalStateException(impossible); }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static RuntimeException mismatch() {
        return new TextResponseValidationException(TextResponseValidationException.Reason.TOOL_STREAM_ASSEMBLY_MISMATCH, -1, -1).asException();
    }

    private static final class ArgumentDigest {
        final MessageDigest upstream = sha256();
        long fragments, characters;
        int assembledCharacters = -1;
        String assembledHash;
        boolean matches, validJson;
    }
}
