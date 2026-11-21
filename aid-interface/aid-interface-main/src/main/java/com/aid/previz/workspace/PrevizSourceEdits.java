package com.aid.previz.workspace;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Prepare every exact replacement before the caller commits one immutable source version. */
public final class PrevizSourceEdits {
    private PrevizSourceEdits() { }
    public record Prepared(Map<String, byte[]> files, List<Integer> replacements) { }
    public record TextEdit(byte[] bytes,List<Integer> replacements) { }

    public static Prepared prepare(PrevizSourceStore store, Long actor, String project, JSONObject input) throws java.io.IOException {
        Map<String,byte[]> files = new TreeMap<>(store.read(actor, project, input.getString("expectedVersion")));
        String path = input.getString("path"); byte[] original = files.get(path);
        if(original == null) throw new IllegalArgumentException("待编辑文件不存在");
        TextEdit edited=apply(original,input.getJSONArray("edit"),store.maximumFileBytes());
        files.put(path,edited.bytes());return new Prepared(files,edited.replacements());
    }
    public static TextEdit apply(byte[] original,JSONArray edits,int maximumBytes)throws java.io.IOException {
        if(original==null||maximumBytes<1||original.length>maximumBytes)throw new IllegalArgumentException("待编辑文件超过限额");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(original)).toString();
        if(edits == null || edits.isEmpty()) throw new IllegalArgumentException("精确编辑列表不能为空");
        List<Integer> counts = new ArrayList<>();
        for(Object raw : edits) {
            if(!(raw instanceof JSONObject edit) || !Set.of("old","new","replace_all").containsAll(edit.keySet())
                    || !(edit.get("old") instanceof String old) || old.isEmpty() || !(edit.get("new") instanceof String replacement)
                    || old.equals(replacement) || edit.containsKey("replace_all") && !(edit.get("replace_all") instanceof Boolean))
                throw new IllegalArgumentException("精确编辑字段无效");
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(replacement));
            int count = 0, position = 0;
            while((position = text.indexOf(old, position)) >= 0) { count++; position += old.length(); }
            if(count == 0 || !edit.getBooleanValue("replace_all") && count != 1)
                throw new IllegalArgumentException("精确原文未唯一匹配，未保存任何修改");
            long size = text.length() + (long) count * (replacement.length() - old.length());
            if(size > maximumBytes) throw new IllegalArgumentException("编辑后文件超过限额");
            text = text.replace(old, replacement);
            if(text.getBytes(StandardCharsets.UTF_8).length > maximumBytes) throw new IllegalArgumentException("编辑后文件超过限额");
            counts.add(count);
        }
        return new TextEdit(text.getBytes(StandardCharsets.UTF_8),List.copyOf(counts));
    }
}
