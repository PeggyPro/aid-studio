package com.aid.previz.workspace;

import com.alibaba.fastjson2.*;
import com.google.re2j.Pattern;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Project-relative, bounded discovery. Patterns never execute a backtracking regex engine. */
public final class PrevizSourceSearch {
    private static final Set<String> SKIP=Set.of("node_modules",".git",".next","dist","build","target");
    private PrevizSourceSearch() { }
    private static Pattern compile(String expression) {
        try{return Pattern.compile(expression);}
        catch(com.google.re2j.PatternSyntaxException invalid){throw new IllegalArgumentException("RE2模式无效或使用了不支持的语法",invalid);}
    }
    public static String path(String value) {
        if(value==null||value.length()>1024||value.contains("\\")||value.contains(":")||value.indexOf('\0')>=0)throw new IllegalArgumentException("工程路径无效");
        if(value.equals("/workspace"))return "";
        if(value.startsWith("/workspace/"))value=value.substring(11);
        while(value.endsWith("/"))value=value.substring(0,value.length()-1);
        if(value.startsWith("/")||Arrays.stream(value.split("/",-1)).anyMatch(part->part.equals(".")||part.equals("..")))throw new IllegalArgumentException("工程路径越界");
        return value;
    }
    private static List<String> expand(String value) {
        List<String> result=new ArrayList<>();ArrayDeque<String> pending=new ArrayDeque<>();pending.add(value);
        while(!pending.isEmpty()) {
            String item=pending.removeFirst();int open=item.indexOf('{');
            if(open<0){if(item.indexOf('}')>=0)throw new IllegalArgumentException("路径模式括号无效");result.add(item);}
            else {
                int depth=1,close=open+1;
                for(;close<item.length()&&depth>0;close++){char c=item.charAt(close);if(c=='{')depth++;else if(c=='}')depth--;}
                if(depth!=0)throw new IllegalArgumentException("路径模式括号无效");close--;
                String inner=item.substring(open+1,close);int start=0;depth=0;
                for(int i=0;i<=inner.length();i++) {
                    if(i<inner.length()){char c=inner.charAt(i);if(c=='{')depth++;else if(c=='}')depth--;}
                    if(i==inner.length()||inner.charAt(i)==','&&depth==0){pending.add(item.substring(0,open)+inner.substring(start,i)+item.substring(close+1));start=i+1;}
                }
            }
            if(result.size()+pending.size()>64)throw new IllegalArgumentException("路径模式展开最多64项");
        }
        return result;
    }
    private static Pattern compileGlob(String glob) {
        StringBuilder regex=new StringBuilder("^");
        for(int i=0;i<glob.length();i++) {
            char c=glob.charAt(i);
            if(c=='*') {
                if(i+1<glob.length()&&glob.charAt(i+1)=='*'){i++;if(i+1<glob.length()&&glob.charAt(i+1)=='/'){i++;regex.append("(?:.*/)?");}else regex.append(".*");}
                else regex.append("[^/]*");
            }else if(c=='?')regex.append("[^/]");
            else if(c=='[') {
                int end=glob.indexOf(']',i+1);if(end<0)throw new IllegalArgumentException("路径字符集合未闭合");
                String chars=glob.substring(i+1,end);if(chars.isEmpty()||chars.contains("/"))throw new IllegalArgumentException("路径字符集合无效");
                regex.append('[').append(chars.startsWith("!")?"^"+chars.substring(1):chars).append(']');i=end;
            }else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return compile(regex.append('$').toString());
    }
    private static boolean skipped(String filename,String explicit) {
        var requested=Arrays.asList(explicit.split("/"));
        return Arrays.stream(filename.split("/")).anyMatch(part->SKIP.contains(part)&&!requested.contains(part));
    }
    public static JSONObject glob(List<PrevizSourceStore.FileInfo> files,String requested) {
        String normalized=path(requested);var variants=expand(normalized).stream().map(PrevizSourceSearch::path).toList();
        var patterns=variants.stream().map(PrevizSourceSearch::compileGlob).toList();
        var matches=files.stream().filter(file->{for(int i=0;i<patterns.size();i++)if(!skipped(file.path(),variants.get(i))&&patterns.get(i).matcher(file.path()).matches())return true;return false;})
                .sorted(Comparator.comparingLong(PrevizSourceStore.FileInfo::modifiedMillis).reversed().thenComparing(PrevizSourceStore.FileInfo::path)).toList();
        return JSONObject.of("paths",matches.stream().limit(1000).map(PrevizSourceStore.FileInfo::path).toList(),"truncated",matches.size()>1000);
    }
    public static JSONObject list(List<PrevizSourceStore.FileInfo> files,String requested) {
        String directory=path(requested),prefix=directory.isEmpty()?"":directory+"/";
        if(files.stream().anyMatch(file->file.path().equals(directory)))throw new IllegalArgumentException("需要目录路径，不能传入文件");
        Map<String,JSONObject> entries=new TreeMap<>();
        for(var file:files)if(file.path().startsWith(prefix)) {
            String relative=file.path().substring(prefix.length());int slash=relative.indexOf('/');String child=slash<0?relative:relative.substring(0,slash);
            entries.putIfAbsent(child,JSONObject.of("path",prefix+child,"isDirectory",slash>=0,"size",slash<0?file.size():0));
        }
        if(!directory.isEmpty()&&entries.isEmpty())throw new IllegalArgumentException("目录不存在");
        var sorted=entries.values().stream().sorted(Comparator.comparing((JSONObject item)->!item.getBooleanValue("isDirectory")).thenComparing(item->item.getString("path"))).toList();
        return JSONObject.of("path",directory,"entries",sorted.stream().limit(1000).toList(),"truncated",sorted.size()>1000);
    }
    private static String excerpt(String value){return value.codePointCount(0,value.length())>2000?value.substring(0,value.offsetByCodePoints(0,2000)):value;}
    public static void validateGrep(String expression,String include,int before,int after){
        if(expression==null||expression.isEmpty()||expression.length()>8192||before<0||before>10||after<0||after>10)throw new IllegalArgumentException("搜索参数无效");
        compile(expression);if(include!=null){if(include.contains("{")||include.contains("}"))throw new IllegalArgumentException("搜索include不支持花括号展开");compileGlob(path(include));}
    }
    public static JSONObject grep(Map<String,byte[]> files,String requested,String expression,String include,int before,int after) throws java.io.IOException {
        String directory=path(requested),prefix=directory.isEmpty()?"":directory+"/";
        if(files.containsKey(directory))throw new IllegalArgumentException("需要目录路径，不能传入文件");
        if(!directory.isEmpty()&&files.keySet().stream().noneMatch(name->name.startsWith(prefix)))throw new IllegalArgumentException("目录不存在");
        if(expression==null||expression.isEmpty()||expression.length()>8192||before<0||before>10||after<0||after>10)throw new IllegalArgumentException("搜索参数无效");
        if(include!=null&&(include.contains("{")||include.contains("}")))throw new IllegalArgumentException("搜索include不支持花括号展开");
        Pattern pattern=compile(expression),filter=include==null?null:compileGlob(path(include));
        JSONArray matches=new JSONArray();int scanned=0,skipped=0,total=0;boolean truncated=false;
        outer: for(var entry:new TreeMap<>(files).entrySet()) {
            String name=entry.getKey();if(!name.startsWith(prefix)||skipped(name,directory+"/"+Objects.toString(include,"")))continue;
            String relative=name.substring(prefix.length()),filterName=include!=null&&!include.contains("/")?relative.substring(relative.lastIndexOf('/')+1):relative;
            if(filter!=null&&!filter.matcher(filterName).matches())continue;
            String content;
            try { content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(entry.getValue())).toString(); }
            catch(CharacterCodingException binary){skipped++;continue;}
            if(content.indexOf('\0')>=0){skipped++;continue;}scanned++;
            String[] lines=content.split("\\r\\n|\\r|\\n",-1);
            for(int line=0;line<lines.length;line++)if(pattern.matcher(lines[line]).find()) {
                JSONObject match=JSONObject.of("path",name,"line",line+1,"text",excerpt(lines[line]));JSONArray preceding=new JSONArray(),following=new JSONArray();
                for(int i=Math.max(0,line-before);i<line;i++){String text=excerpt(lines[i]);if(!text.equals(lines[i]))truncated=true;preceding.add(JSONObject.of("line",i+1,"text",text));}
                for(int i=line+1;i<Math.min(lines.length,line+after+1);i++){String text=excerpt(lines[i]);if(!text.equals(lines[i]))truncated=true;following.add(JSONObject.of("line",i+1,"text",text));}
                match.put("before",preceding);match.put("after",following);int size=match.toJSONString().getBytes(StandardCharsets.UTF_8).length;
                while(total+size+1>100*1024-512&&(!following.isEmpty()||!preceding.isEmpty())) {
                    truncated=true;if(!following.isEmpty())following.remove(following.size()-1);else preceding.remove(0);
                    size=match.toJSONString().getBytes(StandardCharsets.UTF_8).length;
                }
                if(matches.size()>=1000||total+size+1>100*1024-512){truncated=true;break outer;}
                if(!match.getString("text").equals(lines[line]))truncated=true;
                matches.add(match);total+=size+1;
            }
        }
        return JSONObject.of("matches",matches,"filesScanned",scanned,"skippedFiles",skipped,"truncated",truncated);
    }
}
