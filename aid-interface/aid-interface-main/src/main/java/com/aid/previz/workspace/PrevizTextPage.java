package com.aid.previz.workspace;

import com.alibaba.fastjson2.JSONObject;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded text projection; source bytes and line endings are never rewritten. */
public final class PrevizTextPage {
    private PrevizTextPage() { }
    public static JSONObject read(byte[] bytes, int offset, int limit) throws java.io.IOException {
        if (offset < 1 || limit < 1 || limit > 1000) throw new IllegalArgumentException("读取起始行或行数无效");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        int line = 1, start = 0, returned = 0, outputBytes = 0;
        boolean lineTruncated = false, byteTruncated = false;
        String previousEnding = "";
        StringBuilder content = new StringBuilder();
        for (int cursor = 0; cursor <= text.length(); cursor++) {
            if (cursor < text.length() && text.charAt(cursor) != '\r' && text.charAt(cursor) != '\n') continue;
            if (line >= offset && returned < limit && !byteTruncated) {
                String value = text.substring(start, cursor);
                int points = value.codePointCount(0, value.length());
                if (points > 2000) { value = value.substring(0, value.offsetByCodePoints(0, 2000)); lineTruncated = true; }
                int size = value.getBytes(StandardCharsets.UTF_8).length + (returned == 0 ? 0 : previousEnding.length());
                if (outputBytes + size > 100 * 1024) byteTruncated = true;
                else { if (returned > 0) content.append(previousEnding); content.append(value); outputBytes += size; returned++; }
            }
            if (cursor == text.length()) break;
            previousEnding = String.valueOf(text.charAt(cursor));
            if (text.charAt(cursor) == '\r' && cursor + 1 < text.length() && text.charAt(cursor + 1) == '\n') { previousEnding = "\r\n"; cursor++; }
            start = cursor + 1; line++;
        }
        boolean remaining = (long) offset + returned <= line;
        return JSONObject.of("content", content.toString(), "offset", offset, "linesReturned", returned,
                "totalLines", line, "truncated", remaining || lineTruncated || byteTruncated,
                "lineTruncated", lineTruncated, "nextOffset", remaining ? offset + returned : null);
    }
}
