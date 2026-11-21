package com.aid.previz;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound JSON before deserialization, including requests without Content-Length. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE + 30)
@ConditionalOnProperty(prefix="aid.previz",name="enabled",havingValue="true")
public class PrevizAssetRequestLimitFilter extends OncePerRequestFilter {
    @org.springframework.beans.factory.annotation.Value("${aid.previz.documents.maximum-bytes:16777216}")
    private int documentMaximumBytes=16777216;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/user/previz/asset/")&&!request.getServletPath().startsWith("/api/user/previz/document/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        int maximum=request.getServletPath().equals("/api/user/previz/asset/upload/chunk")?384*1024:16*1024;
        if(request.getServletPath().equals("/api/user/previz/document/save"))maximum=Math.min(64*1024*1024,Math.max(1024,documentMaximumBytes))+16384;
        final int byteLimit=maximum;
        if(request.getContentLengthLong()>maximum){response.setStatus(413);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":413,\"msg\":\"三维资产请求超过大小限制\"}");return;}
        chain.doFilter(new HttpServletRequestWrapper(request){
            private ServletInputStream bounded;
            @Override public ServletInputStream getInputStream()throws IOException {
                if(bounded!=null)return bounded;
                ServletInputStream source=super.getInputStream();
                bounded=new ServletInputStream(){
                    private int consumed;
                    private int checked(int count)throws IOException{if(count>0&&((long)consumed+count)>byteLimit)throw new IOException("PREVIZ_ASSET_REQUEST_TOO_LARGE");if(count>0)consumed+=count;return count;}
                    @Override public int read()throws IOException{int value=source.read();checked(value<0?-1:1);return value;}
                    @Override public int read(byte[] bytes,int offset,int length)throws IOException{return checked(source.read(bytes,offset,Math.min(length,byteLimit-consumed+1)));}
                    @Override public boolean isFinished(){return source.isFinished();}
                    @Override public boolean isReady(){return source.isReady();}
                    @Override public void setReadListener(ReadListener listener){source.setReadListener(listener);}
                    @Override public void close()throws IOException{source.close();}
                };return bounded;
            }
            @Override public BufferedReader getReader()throws IOException{return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);
    }
}
