package com.aid.aid.util;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * 公告和常见问题的富文本净化。
 * @author 视觉AID
 */
public final class RichTextSanitizer {
    private static final Safelist ALLOWED = Safelist.relaxed()
            .addTags("s", "u", "span", "hr")
            .addAttributes("img", "width", "height")
            .addAttributes("td", "colspan", "rowspan")
            .preserveRelativeLinks(true);

    private RichTextSanitizer() {
    }

    public static String sanitize(String content) {
        return content == null ? null : Jsoup.clean(content, "https://relative.invalid/", ALLOWED);
    }
}
