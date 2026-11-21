package com.aid.canvas.publication.service;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.*;
import org.springframework.stereotype.Component;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** 只检查原始文件与引用闭包，不转换格式，不改顶点、轴向、材质或变换。 */
@Component public class CanvasModelBundleInspector {
    public record Bundle(String format,String rootPath,Map<String,byte[]> files,JSONArray references) { }
    private static final Set<String> TYPES=Set.of("glb","gltf","fbx","obj","mtl","bin","png","jpg","jpeg","webp","ktx2","dds","tga","bmp","gif");
    private static void fail(String s){throw new ServiceException(s);}
    public static String path(String value) {
        if(value==null||value.length()>512||value.isBlank()||value.startsWith("/")||value.contains("\\")||value.contains(":")||value.contains("?")||value.contains("#")||value.chars().anyMatch(c->c<32)||value.contains("%"))fail("模型包文件路径无效");
        String normalized=Path.of(value).normalize().toString().replace('\\','/');
        if(!normalized.equals(value)||normalized.startsWith("..")||Arrays.asList(value.split("/")).contains(".."))fail("模型包路径不能穿越或重名");return value;
    }
    public static String extension(String p){int at=p.lastIndexOf('.');if(at<0)throw new ServiceException("模型包文件缺少扩展名");return p.substring(at+1).toLowerCase(Locale.ROOT);}
    public Bundle inspect(String root,Map<String,byte[]> input) {
        root=path(root);if(input==null||input.isEmpty()||input.size()>256||!input.containsKey(root))fail("模型包缺少根文件或文件数超限");
        Map<String,byte[]> files=new LinkedHashMap<>();Set<String> casePaths=new HashSet<>();long total=0;
        for(var e:input.entrySet()) {String p=path(e.getKey());byte[] b=e.getValue();if(!casePaths.add(p.toLowerCase(Locale.ROOT))||b==null||b.length==0||b.length>67108864)fail("模型包文件重名、为空或超限");if(!TYPES.contains(extension(p)))fail("模型包包含不支持的文件类型");total+=b.length;if(total>268435456)fail("模型包总量超过256MiB");files.put(p,b);}
        String format=extension(root);if(!Set.of("glb","gltf","fbx","obj").contains(format))fail("模型根文件只支持GLB/GLTF/FBX/OBJ");
        JSONArray references=new JSONArray();
        for(var e:files.entrySet()) {
            String p=e.getKey(),ext=extension(p);byte[] bytes=e.getValue();
            switch(ext) {
                case "glb" -> gltf(p,glb(bytes),files,references);
                case "gltf" -> gltf(p,json(bytes),files,references);
                case "obj" -> textDependencies(p,bytes,files,references,false);
                case "mtl" -> textDependencies(p,bytes,files,references,true);
                case "fbx" -> fbx(p,bytes,files,references,p.equals(root));
                default -> { /* 依赖文件原字节保留；文件大小有界，未解释任何可执行脚本。 */ }
            }
        }
        if(format.equals("gltf")||format.equals("glb"))requireGltfGeometry(format.equals("glb")?glb(files.get(root)):json(files.get(root)));
        if(format.equals("obj")&&!Pattern.compile("(?m)^\\s*f\\s+\\S+\\s+\\S+\\s+\\S+").matcher(decode(files.get(root))).find())fail("OBJ根模型缺少可渲染面");
        return new Bundle(format,root,Collections.unmodifiableMap(files),references);
    }
    private static JSONObject json(byte[] b){try{return JSON.parseObject(decode(b));}catch(Exception e){throw new ServiceException("模型JSON无效");}}
    private static String decode(byte[] b){try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();}catch(Exception e){throw new ServiceException("模型文本必须使用UTF-8");}}
    static JSONObject glb(byte[] b){
        if(b.length<20)throw new ServiceException("GLB头无效");ByteBuffer v=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        if(v.getInt()!=0x46546c67||v.getInt()!=2||v.getInt()!=b.length)fail("GLB版本或长度无效");
        JSONObject root=null;int chunks=0;
        while(v.hasRemaining()){
            if(v.remaining()<8)fail("GLB分块不完整");
            int size=v.getInt(),type=v.getInt();
            if(size<0||size%4!=0||size>v.remaining())fail("GLB分块长度无效");
            if(type==0x4e4f534a){
                if(root!=null||chunks!=0)fail("GLB JSON分块重复");
                byte[] data=new byte[size];v.get(data);root=json(data);
            }else if(type==0x004e4942) v.position(v.position()+size);
            else fail("GLB包含未知分块");
            chunks++;
        }
        if(root==null)fail("GLB缺少JSON分块");return root;
    }
    private static void gltf(String owner,JSONObject root,Map<String,byte[]> files,JSONArray refs){
        JSONObject asset=root.getJSONObject("asset");if(asset==null||!"2.0".equals(asset.getString("version")))fail("只支持glTF 2.0");
        walkUris(owner,root,files,refs,0,new int[1]);
    }
    private static void requireGltfGeometry(JSONObject root){
        JSONArray scenes=root.getJSONArray("scenes"),nodes=root.getJSONArray("nodes"),meshes=root.getJSONArray("meshes"),accessors=root.getJSONArray("accessors");
        int selected=root.getIntValue("scene",0);
        if(scenes==null||selected<0||selected>=scenes.size()||nodes==null||meshes==null||accessors==null)fail("模型缺少可渲染场景或几何数据");
        JSONArray initial=scenes.getJSONObject(selected).getJSONArray("nodes");if(initial==null||initial.isEmpty())fail("模型场景为空");
        Deque<Integer> pending=new ArrayDeque<>();Set<Integer> seen=new HashSet<>();for(Object raw:initial)pending.add(geometryIndex(raw,nodes.size()));
        while(!pending.isEmpty()){
            int index=pending.removeFirst();if(!seen.add(index))continue;JSONObject node=nodes.getJSONObject(index);
            if(node.containsKey("mesh")){JSONObject mesh=meshes.getJSONObject(geometryIndex(node.get("mesh"),meshes.size()));JSONArray primitives=mesh.getJSONArray("primitives");
                if(primitives!=null)for(Object entry:primitives){JSONObject primitive=(JSONObject)entry,attributes=primitive.getJSONObject("attributes");if(attributes!=null&&attributes.containsKey("POSITION")){JSONObject accessor=accessors.getJSONObject(geometryIndex(attributes.get("POSITION"),accessors.size()));if(accessor.getLongValue("count")>0)return;}}
            }
            JSONArray children=node.getJSONArray("children");if(children!=null)for(Object child:children)pending.add(geometryIndex(child,nodes.size()));
        }
        fail("模型当前场景没有可渲染顶点");
    }
    private static int geometryIndex(Object raw,int size){if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.intValue()||n.intValue()<0||n.intValue()>=size)throw new ServiceException("模型场景索引无效");return n.intValue();}
    private static void walkUris(String owner,Object value,Map<String,byte[]> files,JSONArray refs,int depth,int[] count){
        if(depth>64||++count[0]>1000000)fail("模型结构过深或过大");
        if(value instanceof Map<?,?> m)for(var e:m.entrySet()){if("uri".equals(e.getKey())){if(!(e.getValue() instanceof String))fail("模型URI必须为字符串");reference(owner,(String)e.getValue(),files,refs,false);}else walkUris(owner,e.getValue(),files,refs,depth+1,count);}
        else if(value instanceof Iterable<?> a)for(Object child:a)walkUris(owner,child,files,refs,depth+1,count);
    }
    private static void reference(String owner,String uri,Map<String,byte[]> files,JSONArray refs,boolean fbx){
        if(uri==null||uri.isBlank())return;
        if(uri.startsWith("data:")){if(!uri.matches("(?s)data:[a-zA-Z0-9.+/-]+;base64,[a-zA-Z0-9+/=\\r\\n]+"))fail("模型内嵌资源编码无效");return;}
        String decoded;
        try{decoded=java.net.URLDecoder.decode(uri.replace("+","%2B"),StandardCharsets.UTF_8);}catch(Exception e){throw new ServiceException("模型引用编码无效");}
        decoded=decoded.replace('\\','/');String resolved=null;
        if(fbx&&(decoded.matches("^[A-Za-z]:/.*")||decoded.startsWith("/"))) {
            String absolute=decoded;List<String> suffixes=files.keySet().stream().filter(k->absolute.endsWith("/"+k)).sorted(Comparator.comparingInt(String::length).reversed()).toList();
            if(!suffixes.isEmpty())resolved=suffixes.get(0);
            else {String base=decoded.substring(decoded.lastIndexOf('/')+1);List<String> matches=files.keySet().stream().filter(k->k.equals(base)||k.endsWith("/"+base)).toList();if(matches.size()==1)resolved=matches.get(0);}
        } else {
            if(decoded.contains(":")||decoded.startsWith("/")||decoded.contains("?")||decoded.contains("#")||decoded.chars().anyMatch(c->c<32))fail("模型不得引用未登记外部地址");
            Path parent=Path.of(owner).getParent();String p=(parent==null?Path.of(decoded):parent.resolve(decoded)).normalize().toString().replace('\\','/');
            if(p.startsWith("..")||!files.containsKey(p))fail("模型依赖缺失："+owner);resolved=p;
        }
        if(resolved==null||!files.containsKey(resolved))fail("模型材质或贴图依赖缺失："+owner);
        JSONObject row=JSONObject.of("ownerPath",owner,"uri",uri,"path",resolved);if(!refs.contains(row))refs.add(row);
    }
    private static void textDependencies(String owner,byte[] bytes,Map<String,byte[]> files,JSONArray refs,boolean mtl){
        String text=decode(bytes);if(!mtl&&!Pattern.compile("(?m)^\\s*v\\s+[-+.0-9]").matcher(text).find())fail("OBJ缺少顶点数据");
        for(String line:text.split("\\R")){
            line=line.strip();if(line.isEmpty()||line.startsWith("#"))continue;String[] parts=line.split("\\s+",2);if(parts.length<2)continue;
            String kind=parts[0].toLowerCase(Locale.ROOT),tail=parts[1].strip();
            if(!mtl&&kind.equals("mtllib")) {if(resolves(owner,tail,files))reference(owner,tail,files,refs,false);else for(String name:tail.split("\\s+"))reference(owner,name,files,refs,false);}
            if(mtl&&(kind.startsWith("map_")||Set.of("bump","disp","decal","norm","refl").contains(kind))){
                String[] tokens=tail.split("\\s+");int index=0;
                while(index<tokens.length&&tokens[index].startsWith("-")){String option=tokens[index++];int n=switch(option){case "-mm"->2;case "-s","-o","-t"->3;case "-bm","-clamp","-blendu","-blendv","-boost","-texres","-imfchan","-type"->1;default->throw new ServiceException("MTL贴图选项无法确认完整依赖");};int used=0;while(index<tokens.length&&used<n){if(n==3&&!tokens[index].matches("[-+0-9.eE]+"))break;index++;used++;}}
                if(index>=tokens.length)fail("MTL贴图引用为空");reference(owner,String.join(" ",Arrays.copyOfRange(tokens,index,tokens.length)).replaceAll("^\"|\"$",""),files,refs,false);
            }
        }
    }
    private static boolean resolves(String owner,String value,Map<String,byte[]> files){try{Path p=Path.of(owner).getParent();return files.containsKey((p==null?Path.of(value):p.resolve(value)).normalize().toString().replace('\\','/'));}catch(Exception ex){return false;}}
    private static void fbx(String owner,byte[] bytes,Map<String,byte[]> files,JSONArray refs,boolean requireGeometry){
        if(bytes.length>27&&new String(bytes,0,18,StandardCharsets.US_ASCII).equals("Kaydara FBX Binary")) {
            ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);b.position(23);int version=b.getInt();Set<String> dependencies=new LinkedHashSet<>();Set<String> embedded=new HashSet<>();int[] count={0};
            int[] geometry=new int[2];while(b.remaining()>(version>=7500?25:13)){if(fbxNode(b,version,dependencies,embedded,0,count,geometry)==0)break;}
            for(String uri:dependencies)if(!embedded.contains(uri))reference(owner,uri,files,refs,true);
            if(requireGeometry&&(geometry[0]==0||geometry[1]==0))fail("FBX根模型缺少顶点或多边形");
        } else {
            String text=decode(bytes);if(!text.contains("FBXHeaderExtension"))fail("FBX文件头无效");
            Set<String> embedded=new HashSet<>();Matcher videos=Pattern.compile("Video\\s*:[^{]+\\{").matcher(text);
            while(videos.find()){int start=videos.end(),level=1,end=start;boolean quote=false,escape=false;for(;end<text.length()&&level>0;end++){char c=text.charAt(end);if(escape){escape=false;continue;}if(c=='\\'){escape=true;continue;}if(c=='\"')quote=!quote;if(!quote){if(c=='{')level++;else if(c=='}')level--;}}if(level!=0)fail("FBX文本块不完整");String block=text.substring(start,end-1);if(Pattern.compile("Content\\s*:\\s*,?\\s*\"[^\"]+\"",Pattern.DOTALL).matcher(block).find()){Matcher names=Pattern.compile("(?:RelativeFilename|FileName|Filename)\\s*:\\s*\"([^\"]+)\"").matcher(block);while(names.find())embedded.add(names.group(1));}}
            Matcher m=Pattern.compile("(?m)^\\s*(?:RelativeFilename|FileName|Filename)\\s*:\\s*\"([^\"]+)\"").matcher(text);
            while(m.find())if(!embedded.contains(m.group(1)))reference(owner,m.group(1),files,refs,true);
            if(requireGeometry&&(!Pattern.compile("Vertices\\s*:\\s*\\*[1-9][0-9]*").matcher(text).find()||!Pattern.compile("PolygonVertexIndex\\s*:\\s*\\*[1-9][0-9]*").matcher(text).find()))fail("FBX根模型缺少顶点或多边形");
        }
    }
    private static long fbxNode(ByteBuffer b,int version,Set<String> dependencies,Set<String> embedded,int depth,int[] count,int[] geometry){
        if(depth>64||++count[0]>1000000)fail("FBX层级或节点数超限");int begin=b.position(),header=version>=7500?25:13;if(b.remaining()<header)fail("FBX节点不完整");
        long end=version>=7500?b.getLong():Integer.toUnsignedLong(b.getInt()),props=version>=7500?b.getLong():Integer.toUnsignedLong(b.getInt()),length=version>=7500?b.getLong():Integer.toUnsignedLong(b.getInt());int n=Byte.toUnsignedInt(b.get());
        if(end==0&&props==0&&length==0&&n==0)return 0;
        if(end<=begin||end>b.limit()||props>1000000||length<0||length>end-b.position()-n||n>b.remaining())fail("FBX节点长度无效");
        byte[] name=new byte[n];b.get(name);String kind=new String(name,StandardCharsets.US_ASCII);int propEnd=Math.toIntExact(b.position()+length);List<String> values=new ArrayList<>();boolean content=false;
        for(long i=0;i<props;i++){if(b.position()>=propEnd)fail("FBX属性不完整");char type=(char)b.get();int size;
            switch(type){case 'Y'->size=2;case 'C'->size=1;case 'I','F'->size=4;case 'D','L'->size=8;
                case 'S','R'->{if(b.remaining()<4)throw new ServiceException("FBX属性无效");size=b.getInt();if(size<0||size>propEnd-b.position())fail("FBX字符串越界");byte[] data=new byte[size];b.get(data);if(type=='S')values.add(new String(data,StandardCharsets.UTF_8));if(kind.equals("Content")&&size>0)content=true;continue;}
                case 'f','d','l','i','b','c'->{if(b.remaining()<12)throw new ServiceException("FBX数组无效");long elements=Integer.toUnsignedLong(b.getInt());int encoding=b.getInt();size=b.getInt();if(elements>10000000||!Set.of(0,1).contains(encoding))fail("FBX数组超限");if(elements>0&&size>0){if(kind.equals("Vertices"))geometry[0]=1;if(kind.equals("PolygonVertexIndex"))geometry[1]=1;}}
                default->throw new ServiceException("FBX属性类型不支持");}
            if(size<0||size>propEnd-b.position())fail("FBX属性越界");b.position(b.position()+size);
        }
        if(b.position()!=propEnd)fail("FBX属性长度不一致");Set<String> local=new LinkedHashSet<>(),localEmbedded=new HashSet<>();
        if(Set.of("FileName","Filename","RelativeFilename").contains(kind))local.addAll(values);
        while(b.position()<end){int before=b.position();if(fbxNode(b,version,local,localEmbedded,depth+1,count,geometry)==0)break;if(b.position()<=before)fail("FBX解析未前进");}
        b.position(Math.toIntExact(end));if(kind.equals("Content")&&content)localEmbedded.add("*");
        if(kind.equals("Video")&&localEmbedded.remove("*"))embedded.addAll(local);else dependencies.addAll(local);
        embedded.addAll(localEmbedded);return end;
    }
}
