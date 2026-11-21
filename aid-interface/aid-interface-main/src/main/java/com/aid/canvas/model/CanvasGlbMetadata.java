package com.aid.canvas.model;

import com.alibaba.fastjson2.*;
import com.aid.common.exception.ServiceException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads static bounds from actual uncompressed POSITION bytes, retaining the file's original scale. */
public final class CanvasGlbMetadata {
    private CanvasGlbMetadata() { }
    public static JSONObject read(byte[] bytes) {
        try { return inspect(bytes); }
        catch (ServiceException exception) { throw exception; }
        catch (RuntimeException exception) { throw new ServiceException("模型几何结构或二进制数据无效"); }
    }
    private static JSONObject inspect(byte[] bytes) {
        ByteBuffer file=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(file.remaining()<20||file.getInt()!=0x46546c67||file.getInt()!=2||file.getInt()!=bytes.length)fail("GLB头或长度无效");
        JSONObject gltf=null;ByteBuffer bin=null;
        while(file.hasRemaining()) {
            if(file.remaining()<8)fail("GLB分块不完整");
            int size=file.getInt(),type=file.getInt();
            if(size<0||size%4!=0||size>file.remaining())fail("GLB分块长度无效");
            if(type==0x4e4f534a) {
                if(gltf!=null||file.position()!=20)fail("GLB JSON分块无效");
                byte[] json=new byte[size];file.get(json);gltf=JSON.parseObject(new String(json,StandardCharsets.UTF_8).trim());
            } else if(type==0x004e4942) {
                if(bin!=null)fail("GLB二进制分块重复");
                bin=file.slice().order(ByteOrder.LITTLE_ENDIAN);bin.limit(size);file.position(file.position()+size);
            } else fail("GLB包含不支持的分块");
        }
        if(gltf==null||bin==null)fail("模型缺少JSON或二进制顶点数据");
        JSONArray buffers=gltf.getJSONArray("buffers"),views=gltf.getJSONArray("bufferViews"),accessors=gltf.getJSONArray("accessors");
        JSONArray meshes=gltf.getJSONArray("meshes"),nodes=gltf.getJSONArray("nodes"),scenes=gltf.getJSONArray("scenes");
        if(buffers==null||buffers.size()!=1||views==null||accessors==null||meshes==null||nodes==null||scenes==null)fail("模型缺少完整的内嵌几何数据");
        JSONObject buffer=buffers.getJSONObject(0);
        long byteLength=integer(buffer.get("byteLength"),0,Integer.MAX_VALUE);
        if(buffer.containsKey("uri")||byteLength>bin.limit()||bin.limit()-byteLength>3)fail("模型二进制缓冲区长度不匹配");
        if(nodes.size()>10000||meshes.size()>10000||accessors.size()>20000||views.size()>20000)fail("模型场景对象过多");
        for(Object raw:views) {
            JSONObject view=(JSONObject)raw;
            if(integer(view.getOrDefault("buffer",0),0,0)!=0)fail("模型缓冲区索引无效");
            long offset=integer(view.getOrDefault("byteOffset",0),0,byteLength),length=integer(view.get("byteLength"),1,byteLength);
            if(offset+length>byteLength)fail("模型缓冲区越界");
        }
        Map<Integer,double[]> positions=new HashMap<>();long vertices=0;int primitives=0;
        for(Object raw:meshes) {
            JSONArray entries=((JSONObject)raw).getJSONArray("primitives");
            if(entries==null||entries.isEmpty())fail("模型网格缺少图元");
            for(Object entry:entries) {
                if(++primitives>20000)fail("模型图元数量过多");
                JSONObject primitive=(JSONObject)entry,attributes=primitive.getJSONObject("attributes");
                if(primitive.getJSONObject("extensions")!=null&&primitive.getJSONObject("extensions").containsKey("KHR_draco_mesh_compression"))fail("请上传已解压的GLB几何数据");
                if(attributes==null||!attributes.containsKey("POSITION"))fail("模型图元缺少POSITION数据");
                int position=index(attributes.get("POSITION"),accessors.size());
                for(Object attribute:attributes.values())validateAccessor(accessors.getJSONObject(index(attribute,accessors.size())),views);
                if(primitive.containsKey("indices"))validateAccessor(accessors.getJSONObject(index(primitive.get("indices"),accessors.size())),views);
                if(!positions.containsKey(position)) {
                    JSONObject accessor=accessors.getJSONObject(position);
                    vertices+=integer(accessor.get("count"),1,2000000);
                    if(vertices>2000000)fail("模型顶点数据超过当前容量限制");
                    positions.put(position,positionBounds(accessor,views,bin));
                }
            }
        }
        double[] bounds=emptyBounds();Set<Integer> visited=new HashSet<>();
        int scene=index(gltf.getOrDefault("scene",0),scenes.size());JSONArray roots=scenes.getJSONObject(scene).getJSONArray("nodes");
        if(roots==null||roots.isEmpty())fail("模型场景为空");
        for(Object root:roots)walk(index(root,nodes.size()),identity(),nodes,meshes,positions,bounds,visited,0);
        if(!Double.isFinite(bounds[0]))fail("模型当前场景没有可渲染顶点");
        return JSONObject.of("format","glb","byteSize",bytes.length,"accessorVectorCount",vertices,"meshCount",meshes.size(),
            "skinned",gltf.getJSONArray("skins")!=null&&!gltf.getJSONArray("skins").isEmpty(),"geometryScale","文件原始比例",
            "bounds",JSONObject.of("min",List.of(bounds[0],bounds[1],bounds[2]),"max",List.of(bounds[3],bounds[4],bounds[5]),
                "width",bounds[3]-bounds[0],"height",bounds[4]-bounds[1],"depth",bounds[5]-bounds[2]));
    }
    private static int componentBytes(int type) { return switch(type) {case 5120,5121->1;case 5122,5123->2;case 5125,5126->4;default->throw new ServiceException("模型顶点数据类型不支持");}; }
    private static int components(String type) {return switch(type){case "SCALAR"->1;case "VEC2"->2;case "VEC3"->3;case "VEC4","MAT2"->4;case "MAT3"->9;case "MAT4"->16;default->throw new ServiceException("模型顶点维度无效");};}
    private static JSONObject validateAccessor(JSONObject accessor,JSONArray views) {
        if(accessor.containsKey("sparse"))fail("请将稀疏顶点数据展开后上传GLB");
        JSONObject view=views.getJSONObject(index(accessor.get("bufferView"),views.size()));
        if(view.getJSONObject("extensions")!=null&&view.getJSONObject("extensions").containsKey("EXT_meshopt_compression"))fail("请上传已解压的GLB几何数据");
        int size=componentBytes(accessor.getIntValue("componentType"))*components(accessor.getString("type"));
        long count=integer(accessor.get("count"),1,2000000),offset=integer(accessor.getOrDefault("byteOffset",0),0,Integer.MAX_VALUE);
        long stride=integer(view.getOrDefault("byteStride",size),size,252);
        if(offset+(count-1)*stride+size>view.getLongValue("byteLength"))fail("模型顶点读取超出缓冲区");
        return view;
    }
    private static double[] positionBounds(JSONObject accessor,JSONArray views,ByteBuffer bin) {
        JSONObject view=validateAccessor(accessor,views);
        if(!"VEC3".equals(accessor.getString("type"))||accessor.getIntValue("componentType")!=5126||accessor.getBooleanValue("normalized"))fail("请将模型POSITION数据导出为FLOAT VEC3");
        int count=accessor.getIntValue("count"),stride=view.getIntValue("byteStride",12);
        int start=view.getIntValue("byteOffset",0)+accessor.getIntValue("byteOffset",0);double[] bounds=emptyBounds();
        for(int vertex=0;vertex<count;vertex++)for(int axis=0;axis<3;axis++){
            double value=finite(bin.getFloat(start+vertex*stride+axis*4));bounds[axis]=Math.min(bounds[axis],value);bounds[axis+3]=Math.max(bounds[axis+3],value);
        }
        return bounds;
    }
    private static void walk(int id,double[] parent,JSONArray nodes,JSONArray meshes,Map<Integer,double[]> positions,double[] bounds,Set<Integer> visited,int depth) {
        if(depth>64||!visited.add(id))fail("模型节点存在循环、重复引用或多个父节点");
        JSONObject node=nodes.getJSONObject(id);double[] local=identity();JSONArray matrix=node.getJSONArray("matrix");
        if(matrix!=null){local=values(matrix,new double[16]);if(local[3]!=0||local[7]!=0||local[11]!=0||local[15]!=1)fail("模型矩阵必须为仿射变换");}
        else {
            double[] t=values(node.getJSONArray("translation"),new double[]{0,0,0}),s=values(node.getJSONArray("scale"),new double[]{1,1,1}),q=values(node.getJSONArray("rotation"),new double[]{0,0,0,1});
            double x=q[0],y=q[1],z=q[2],w=q[3],norm=x*x+y*y+z*z+w*w;
            if(Math.abs(norm-1)>0.001)fail("模型旋转四元数未归一化");
            local=new double[]{(1-2*y*y-2*z*z)*s[0],(2*x*y+2*z*w)*s[0],(2*x*z-2*y*w)*s[0],0,
                (2*x*y-2*z*w)*s[1],(1-2*x*x-2*z*z)*s[1],(2*y*z+2*x*w)*s[1],0,
                (2*x*z+2*y*w)*s[2],(2*y*z-2*x*w)*s[2],(1-2*x*x-2*y*y)*s[2],0,t[0],t[1],t[2],1};
        }
        double[] world=multiply(parent,local);
        if(node.containsKey("mesh"))for(Object raw:meshes.getJSONObject(index(node.get("mesh"),meshes.size())).getJSONArray("primitives")){
            double[] box=positions.get(((JSONObject)raw).getJSONObject("attributes").getIntValue("POSITION"));
            for(int corner=0;corner<8;corner++){
                double x=box[(corner&1)==0?0:3],y=box[(corner&2)==0?1:4],z=box[(corner&4)==0?2:5];
                for(int axis=0;axis<3;axis++){double value=finite(world[axis]*x+world[axis+4]*y+world[axis+8]*z+world[axis+12]);bounds[axis]=Math.min(bounds[axis],value);bounds[axis+3]=Math.max(bounds[axis+3],value);}
            }
        }
        JSONArray children=node.getJSONArray("children");if(children!=null)for(Object child:children)walk(index(child,nodes.size()),world,nodes,meshes,positions,bounds,visited,depth+1);
    }
    private static long integer(Object raw,long min,long max){if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.longValue()||n.longValue()<min||n.longValue()>max)throw new ServiceException("模型索引、数量或长度无效");return n.longValue();}
    private static int index(Object raw,int size){return (int)integer(raw,0,size-1);}
    private static void fail(String message){throw new ServiceException(message);}
    private static double finite(double value){if(!Double.isFinite(value)||Math.abs(value)>100000000)throw new ServiceException("模型坐标无效或超出范围");return value;}
    private static double[] values(JSONArray array,double[] defaults){if(array==null)return defaults;if(array.size()!=defaults.length)throw new ServiceException("模型变换维度无效");double[] result=new double[array.size()];for(int i=0;i<result.length;i++)result[i]=finite(array.getDoubleValue(i));return result;}
    private static double[] emptyBounds(){return new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};}
    private static double[] identity(){return new double[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    private static double[] multiply(double[] a,double[] b){double[] result=new double[16];for(int c=0;c<4;c++)for(int r=0;r<4;r++)for(int k=0;k<4;k++)result[c*4+r]+=a[k*4+r]*b[c*4+k];return result;}
}
