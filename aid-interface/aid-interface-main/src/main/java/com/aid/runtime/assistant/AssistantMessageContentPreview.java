package com.aid.runtime.assistant;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Read-only incremental projection of $.message.content. Never repairs or executes JSON. */
public final class AssistantMessageContentPreview {
    private static final int KEY = 0, COLON = 1, VALUE = 2, AFTER = 3;
    private static final class Frame {
        final boolean object, message;
        final Set<String> keys = new HashSet<>();
        int state;
        String key;
        Frame(boolean object, boolean message) {
            this.object = object; this.message = message; state = object ? KEY : VALUE;
        }
    }
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final StringBuilder token = new StringBuilder();
    private final String rootTextField;
    private boolean started, finished, blocked, string, keyString, capture, escape, literal;
    private int unicodeDigits, unicodeValue, size;
    private char highSurrogate;

    public AssistantMessageContentPreview() { this(null); }
    public AssistantMessageContentPreview(String rootTextField) { this.rootTextField = rootTextField; }

    public String accept(String fragment) {
        if (blocked || fragment == null || fragment.isEmpty()) return "";
        if ((size += fragment.length()) > 262144) { blocked = true; return ""; }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fragment.length() && !blocked; i++) {
            char c = fragment.charAt(i);
            if (string) {
                if (unicodeDigits > 0) {
                    int digit = Character.digit(c, 16);
                    if (digit < 0) { blocked = true; break; }
                    unicodeValue = unicodeValue * 16 + digit;
                    if (--unicodeDigits == 0) decoded((char) unicodeValue, out);
                } else if (escape) {
                    escape = false;
                    if (c == 'u') { unicodeDigits = 4; unicodeValue = 0; }
                    else {
                        char value = switch (c) {
                            case '"', '\\', '/' -> c;
                            case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n';
                            case 'r' -> '\r'; case 't' -> '\t'; default -> 0;
                        };
                        if (value == 0) blocked = true; else decoded(value, out);
                    }
                } else if (c == '\\') escape = true;
                else if (c == '"') {
                    if (highSurrogate != 0) { blocked = true; break; }
                    string = false;
                    Frame frame = frames.peek();
                    if (keyString) {
                        frame.key = token.toString();
                        if (!frame.keys.add(frame.key)) { blocked = true; break; }
                        frame.state = COLON;
                    } else frame.state = AFTER;
                    token.setLength(0);
                } else if (c < 0x20) blocked = true;
                else decoded(c, out);
                continue;
            }
            if (literal) {
                if (!Character.isWhitespace(c) && c != ',' && c != '}' && c != ']') {
                    token.append(c);
                    if (token.length() > 128) blocked = true;
                    continue;
                }
                if (!token.toString().matches("(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)")) {
                    blocked = true; break;
                }
                token.setLength(0); literal = false; frames.peek().state = AFTER;
            }
            if (Character.isWhitespace(c)) continue;
            if (finished) { blocked = true; break; }
            Frame frame = frames.peek();
            if (c == '{' || c == '[') {
                if (frame == null ? started || c != '{' : frame.state != VALUE) { blocked = true; break; }
                boolean message = frames.size() == 1 && frame.object && "message".equals(frame.key);
                if (frames.size() >= 32) { blocked = true; break; }
                frames.push(new Frame(c == '{', message)); started = true;
            } else if (frame == null) blocked = true;
            else if (c == '"') {
                if (frame.state != VALUE && !(frame.object && frame.state == KEY)) { blocked = true; break; }
                string = true; keyString = frame.object && frame.state == KEY;
                capture = !keyString && frame.object && (frame.message && frames.size() == 2 && "content".equals(frame.key)
                        || frames.size() == 1 && rootTextField != null && rootTextField.equals(frame.key));
                token.setLength(0);
            } else if (c == ':') {
                if (!frame.object || frame.state != COLON) blocked = true; else frame.state = VALUE;
            } else if (c == ',') {
                if (frame.state != AFTER) blocked = true; else frame.state = frame.object ? KEY : VALUE;
            } else if (c == '}' || c == ']') {
                if (frame.object != (c == '}') || frame.state != AFTER && frame.state != (frame.object ? KEY : VALUE)) { blocked = true; break; }
                frames.pop();
                if (frames.isEmpty()) finished = true; else frames.peek().state = AFTER;
            } else {
                if (frame.state != VALUE) { blocked = true; break; }
                literal = true; token.append(c);
            }
        }
        return out.toString();
    }

    private void decoded(char c, StringBuilder out) {
        if (keyString) {
            if (token.length() >= 256) { blocked = true; return; }
            token.append(c); return;
        }
        if (!capture) return;
        if (highSurrogate != 0) {
            if (!Character.isLowSurrogate(c)) { blocked = true; return; }
            out.append(highSurrogate).append(c); highSurrogate = 0;
        } else if (Character.isHighSurrogate(c)) highSurrogate = c;
        else if (Character.isLowSurrogate(c)) blocked = true;
        else out.append(c);
    }
}
