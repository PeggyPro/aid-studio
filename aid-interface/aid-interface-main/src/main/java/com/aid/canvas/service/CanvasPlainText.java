package com.aid.canvas.service;

import com.aid.common.exception.ServiceException;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 仅用于画布人类可读正文；通用生成协议、工具 JSON 和 Schema 不经过此类。 */
public final class CanvasPlainText {
    private static final Set<String> PARAGRAPH = Set.of("blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "p", "pre");
    private static final Set<String> LINE = Set.of("address", "article", "dd", "div", "dl", "dt", "hr",
            "li", "ol", "section", "table", "tr", "ul");
    private static final Set<String> STRUCTURAL = Set.of("body", "table", "thead", "tbody", "tfoot", "tr", "ul", "ol", "dl");
    private static final Set<String> HIDDEN = Set.of("script", "style", "template", "noscript", "head", "svg");
    private static final Pattern HIDDEN_STYLE = Pattern.compile("(?i)(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)(?:\\s*!important)?\\s*(?:;|$)");

    private CanvasPlainText() { }

    public static String normalize(String source, String format) {
        if (source == null) throw new ServiceException("文本正文不能为空");
        String value = lineFeeds(source);
        String kind = format == null ? "TEXT" : format.toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "TEXT" -> value;
            case "HTML" -> fromHtml(value);
            case "MARKDOWN" -> fromMarkdown(value);
            default -> throw new ServiceException("文本格式只支持 TEXT、MARKDOWN 或 HTML");
        };
    }

    public static String lineFeeds(String value) {
        return value == null ? null : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String fromHtml(String value) {
        org.jsoup.nodes.Document document = Jsoup.parseBodyFragment(value);
        document.outputSettings().prettyPrint(false);
        StringBuilder out = new StringBuilder(value.length());
        for (Node child : document.body().childNodes()) {
            if (child instanceof TextNode text && text.getWholeText().isBlank()) continue;
            appendHtml(child, out, false);
        }
        return out.toString();
    }

    private static void appendHtml(Node node, StringBuilder out, boolean markdownHtml) {
        if (node instanceof DataNode) return;
        if (node instanceof TextNode text) {
            if (markdownHtml && text.getWholeText().isBlank() && text.parent() instanceof Element parent
                    && STRUCTURAL.contains(parent.normalName())) return;
            out.append(text.getWholeText());
            return;
        }
        if (!(node instanceof Element element)) {
            for (Node child : node.childNodes()) appendHtml(child, out, markdownHtml);
            return;
        }
        String tag = element.normalName();
        if (HIDDEN.contains(tag) || element.hasAttr("hidden") || HIDDEN_STYLE.matcher(element.attr("style")).find()
                || "true".equalsIgnoreCase(element.attr("aria-hidden"))) return;
        if ("br".equals(tag)) {
            out.append('\n');
            return;
        }
        if (PARAGRAPH.contains(tag)) ensureNewlines(out, 2);
        else if (LINE.contains(tag)) ensureNewlines(out, 1);
        if (("td".equals(tag) || "th".equals(tag)) && element.previousElementSibling() != null) out.append('\t');
        for (Node child : element.childNodes()) appendHtml(child, out, markdownHtml);
        if (PARAGRAPH.contains(tag) || LINE.contains(tag)) ensureNewlines(out, 1);
    }

    private static String fromMarkdown(String value) {
        List<org.commonmark.Extension> extensions = List.of(TablesExtension.create());
        String html = HtmlRenderer.builder().extensions(extensions).escapeHtml(false).build()
                .render(Parser.builder().extensions(extensions).build().parse(value));
        // CommonMark's renderer inserts an LF between HTML tags. It is not source text.
        org.jsoup.nodes.Document document = Jsoup.parseBodyFragment(html);
        StringBuilder out = new StringBuilder(value.length());
        for (Node child : document.body().childNodes()) {
            if (child instanceof TextNode text && text.getWholeText().isBlank()) continue;
            appendHtml(child, out, true);
        }
        int trailing = 0;
        for (int i = value.length() - 1; i >= 0 && value.charAt(i) == '\n'; i--) trailing++;
        int outputTrailing = 0;
        for (int i = out.length() - 1; i >= 0 && out.charAt(i) == '\n'; i--) outputTrailing++;
        for (int i = outputTrailing; i < trailing; i++) out.append('\n');
        return out.toString();
    }

    private static void ensureNewlines(StringBuilder out, int required) {
        if (out.isEmpty()) return;
        int present = 0;
        for (int i = out.length() - 1; i >= 0 && out.charAt(i) == '\n'; i--) present++;
        for (int i = present; i < required; i++) out.append('\n');
    }
}
