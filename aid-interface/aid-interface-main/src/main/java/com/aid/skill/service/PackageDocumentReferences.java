package com.aid.skill.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 校验包内 Markdown 文件链接，不读取磁盘或解释提示词中的指令。 */
public final class PackageDocumentReferences {
    private static final Pattern DEFINITION = Pattern.compile(
            "(?m)^ {0,3}\\[(?:\\\\.|[^\\]\\\\\\r\\n])+\\]:[ \\t]*");
    private static final Pattern ESCAPE = Pattern.compile("\\\\([!\"#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~])");
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    private static final Set<String> ROOT_FILES = Set.of("SKILL.md", "SP.md", "system_prompt.md");

    private PackageDocumentReferences() { }

    public record MissingReference(String sourcePath, String targetPath, int line) { }

    /** 包路径必须来自已经校验的文件清单；忽略网址、锚点和非文档资源。 */
    public static List<MissingReference> validate(Map<String, String> files) {
        List<MissingReference> missing = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            if (!file.getKey().endsWith(".md") || file.getValue() == null) continue;
            String text = markdownMask(file.getValue());
            int[] lines = new int[text.length() + 1];
            int line = 1;
            for (int index = 0; index < text.length(); index++) {
                lines[index] = line;
                if (text.charAt(index) == '\n') line++;
            }
            lines[text.length()] = line;
            Set<String> seen = new LinkedHashSet<>();
            Matcher definitions = DEFINITION.matcher(text);
            while (definitions.find()) {
                Destination destination = destination(text, definitions.end());
                if (destination != null) collect(files.keySet(), file.getKey(), destination,
                        text, lines, seen, missing);
            }
            ArrayDeque<Integer> brackets = new ArrayDeque<>();
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if ((character != '[' && character != ']') || escaped(text, index)) continue;
                if (character == '[') { brackets.push(index); continue; }
                if (brackets.isEmpty()) continue;
                brackets.pop();
                if (index + 1 >= text.length() || text.charAt(index + 1) != '(') continue;
                Destination destination = destination(text, index + 2);
                if (destination == null) continue;
                int end = whitespace(text, destination.after());
                if (end < text.length() && (text.charAt(end) == '\"' || text.charAt(end) == '\'')) {
                    char quote = text.charAt(end++);
                    while (end < text.length() && (text.charAt(end) != quote || escaped(text, end))) end++;
                    if (end == text.length()) continue;
                    end = whitespace(text, end + 1);
                }
                if (end >= text.length() || text.charAt(end) != ')') continue;
                collect(files.keySet(), file.getKey(), destination, text, lines, seen, missing);
                index = end;
            }
        }
        return List.copyOf(missing);
    }

    private record Destination(int start, int end, int after) { }

    private static int whitespace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        return index;
    }

    private static boolean escaped(String text, int index) {
        int backslashes = 0;
        while (index > 0 && text.charAt(--index) == '\\') backslashes++;
        return backslashes % 2 == 1;
    }

    private static Destination destination(String text, int index) {
        int start = whitespace(text, index);
        if (start >= text.length()) return null;
        if (text.charAt(start) == '<') {
            for (int cursor = start + 1; cursor < text.length(); cursor++) {
                char character = text.charAt(cursor);
                if (character == '\n' || character == '\r') return null;
                if (character == '>' && !escaped(text, cursor)) {
                    return new Destination(start + 1, cursor, cursor + 1);
                }
            }
            return null;
        }
        int cursor = start;
        int depth = 0;
        while (cursor < text.length()) {
            char character = text.charAt(cursor);
            if (!escaped(text, cursor)) {
                if (Character.isWhitespace(character)) break;
                if (character == '(') depth++;
                if (character == ')') { if (depth == 0) break; depth--; }
            }
            cursor++;
        }
        return cursor == start || depth != 0 ? null : new Destination(start, cursor, cursor);
    }

    private static void collect(Set<String> paths, String sourcePath, Destination destination, String text, int[] lines,
                                Set<String> seen, List<MissingReference> missing) {
            String target = ESCAPE.matcher(text.substring(destination.start(), destination.end())).replaceAll("$1");
            int fragment = target.indexOf('#');
            if (fragment >= 0) target = target.substring(0, fragment);
            int query = target.indexOf('?');
            if (query >= 0) target = target.substring(0, query);
            if (target.isBlank() || SCHEME.matcher(target).matches() || target.startsWith("//")
                    || !isDocument(target)) return;
            String resolved = resolve(sourcePath, target);
            if ((resolved == null || !paths.contains(resolved)) && seen.add(target)) {
                missing.add(new MissingReference(sourcePath, target, lines[destination.start()]));
            }
    }

    private static boolean isDocument(String path) {
        return path.endsWith(".md") || path.endsWith(".txt") || path.endsWith(".json")
                || path.endsWith(".yaml") || path.endsWith(".yml");
    }

    private static String resolve(String source, String target) {
        if (target.startsWith("/") || target.contains("\\") || target.contains("%")) return null;
        String relative = target;
        // 工具参数中的 references/ 和入口文件名使用包根路径；其他链接按当前文件目录解析。
        if (!target.startsWith("references/") && !ROOT_FILES.contains(target)) {
            int slash = source.lastIndexOf('/');
            if (slash >= 0) relative = source.substring(0, slash + 1) + target;
        }
        ArrayDeque<String> segments = new ArrayDeque<>();
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty()) return null;
            if (".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (segments.isEmpty()) return null;
                segments.removeLast();
            } else segments.addLast(segment);
        }
        String result = String.join("/", segments);
        return ROOT_FILES.contains(result) || result.startsWith("references/") ? result : null;
    }

    /** 单次按上下文屏蔽示例；注释里的 fence 和代码里的注释不会影响后文。 */
    private static String markdownMask(String text) {
        char[] result = text.toCharArray();
        char fence = 0;
        int fenceWidth = 0;
        for (int index = 0; index < text.length();) {
            boolean lineStart = index == 0 || text.charAt(index - 1) == '\n';
            if (lineStart) {
                int lineEnd = text.indexOf('\n', index);
                if (lineEnd < 0) lineEnd = text.length();
                int markerStart = index;
                while (markerStart < lineEnd && text.charAt(markerStart) == ' ') markerStart++;
                int indent = markerStart - index;
                char marker = markerStart == lineEnd ? 0 : text.charAt(markerStart);
                int markerEnd = markerStart;
                if (marker == '`' || marker == '~') {
                    while (markerEnd < lineEnd && text.charAt(markerEnd) == marker) markerEnd++;
                }
                int width = markerEnd - markerStart;
                if (fence != 0) {
                    hide(result, index, lineEnd);
                    if (indent <= 3 && marker == fence && width >= fenceWidth
                            && text.substring(markerEnd, lineEnd).isBlank()) fence = 0;
                    index = lineEnd < text.length() ? lineEnd + 1 : lineEnd;
                    continue;
                }
                if (indent <= 3 && width >= 3) {
                    fence = marker;
                    fenceWidth = width;
                    hide(result, index, lineEnd);
                    index = lineEnd < text.length() ? lineEnd + 1 : lineEnd;
                    continue;
                }
                if (indent >= 4 || marker == '\t') {
                    hide(result, index, lineEnd);
                    index = lineEnd < text.length() ? lineEnd + 1 : lineEnd;
                    continue;
                }
            }
            if (text.startsWith("<!--", index) && !escaped(text, index)) {
                int close = text.indexOf("-->", index + 4);
                int end = close < 0 ? text.length() : close + 3;
                hide(result, index, end);
                index = end;
                continue;
            }
            if (text.charAt(index) == '`' && !escaped(text, index)) {
                int after = index + 1;
                while (after < text.length() && text.charAt(after) == '`') after++;
                int width = after - index;
                int close = after;
                while (close < text.length()) {
                    if (text.charAt(close - 1) == '\n') {
                        int lineEnd = text.indexOf('\n', close);
                        if (lineEnd < 0) lineEnd = text.length();
                        if (text.substring(close, lineEnd).matches(" {0,3}(?:`{3,}|~{3,}).*")) {
                            close = text.length();
                            break;
                        }
                    }
                    if (text.charAt(close) != '`') { close++; continue; }
                    int end = close + 1;
                    while (end < text.length() && text.charAt(end) == '`') end++;
                    if (end - close == width) break;
                    close = end;
                }
                if (close < text.length()) {
                    hide(result, index, close + width);
                    index = close + width;
                } else index = after;
                continue;
            }
            index++;
        }
        return new String(result);
    }

    private static void hide(char[] result, int start, int end) {
        for (int index = start; index < end; index++) {
            if (result[index] != '\n' && result[index] != '\r') result[index] = ' ';
        }
    }
}
