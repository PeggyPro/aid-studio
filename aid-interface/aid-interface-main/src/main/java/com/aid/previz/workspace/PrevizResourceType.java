package com.aid.previz.workspace;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/** Format routing uses actual bytes first. Declared hints never override a detected binary format. */
public final class PrevizResourceType {
    private PrevizResourceType(){}
    private static boolean starts(byte[] bytes,int... prefix){if(bytes.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if((bytes[i]&255)!=prefix[i])return false;return true;}
    private static String ascii(byte[] bytes,int from,int length){return bytes.length<from+length?"":new String(bytes,from,length,StandardCharsets.US_ASCII);}
    public static String detect(byte[] bytes,String mediaType,String hint)throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>32*1024*1024)throw new IOException("RESOURCE_SIZE_INVALID");
        if(ascii(bytes,0,5).equals("%PDF-"))return "pdf";
        if(ascii(bytes,0,4).equals("8BPS"))return "psd";
        if(starts(bytes,137,80,78,71,13,10,26,10)||starts(bytes,255,216)||ascii(bytes,0,3).equals("GIF")||ascii(bytes,0,2).equals("BM")||starts(bytes,73,73,42,0)||starts(bytes,77,77,0,42)||ascii(bytes,0,4).equals("RIFF")&&ascii(bytes,8,4).equals("WEBP"))return "image";
        if(starts(bytes,0,1,0,0)||Set.of("OTTO","ttcf","wOFF","wOF2").contains(ascii(bytes,0,4)))return "font";
        if(starts(bytes,80,75,3,4))return officeZip(bytes);
        if(starts(bytes,208,207,17,224,161,177,26,225))try(var ole=new POIFSFileSystem(new ByteArrayInputStream(bytes))){boolean doc=ole.getRoot().hasEntry("WordDocument"),ppt=ole.getRoot().hasEntry("PowerPoint Document");if(doc==ppt)throw new IOException("RESOURCE_OFFICE_FORMAT_AMBIGUOUS");return doc?"doc":"ppt";}
        if(ascii(bytes,0,4).equals("RIFF")&&ascii(bytes,8,4).equals("WAVE")||ascii(bytes,0,3).equals("ID3")||ascii(bytes,0,4).equals("fLaC")||starts(bytes,255,251)||starts(bytes,255,243)||starts(bytes,255,242)||starts(bytes,255,241)||starts(bytes,255,249))throw new IOException("RESOURCE_FORMAT_UNSUPPORTED");
        if(ascii(bytes,4,4).equals("ftyp")){String brand=ascii(bytes,8,4);if(Set.of("avif","avis","heic","heix","mif1").contains(brand))return "image";if(Set.of("M4A ","M4B ","M4P ").contains(brand))throw new IOException("RESOURCE_FORMAT_UNSUPPORTED");return "video";}
        if(starts(bytes,26,69,223,163))return "video";
        if(ascii(bytes,0,4).equals("OggS"))return "video";
        if(bytes.length>10*1024*1024)throw new IOException("RESOURCE_TEXT_TOO_LARGE");
        String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException invalid){throw new IOException("RESOURCE_FORMAT_UNSUPPORTED");}
        if(text.indexOf('\0')>=0)throw new IOException("RESOURCE_FORMAT_UNSUPPORTED");
        int at=skipSpace(text,text.startsWith("\uFEFF")?1:0);if(text.startsWith("<?xml",at)){int end=text.indexOf("?>",at);if(end>=0)at=skipSpace(text,end+2);}
        while(text.startsWith("<!--",at)){int end=text.indexOf("-->",at);if(end<0)break;at=skipSpace(text,end+3);}
        if(java.util.regex.Pattern.compile("<(?:[A-Za-z_][\\w.-]*:)?svg(?:\\s|>)").matcher(text).region(at,text.length()).lookingAt())return "svg";
        if(text.regionMatches(true,at,"<!doctype html",0,14)||text.regionMatches(true,at,"<html",0,5)||"html".equals(hint)||"text/html".equalsIgnoreCase(mediaType))return "html";
        return "md";
    }
    private static int skipSpace(String text,int start){int at=start;while(at<text.length()&&Character.isWhitespace(text.charAt(at)))at++;return at;}
    private static String officeZip(byte[] bytes)throws IOException{
        boolean doc=false,ppt=false;long expanded=0;int entries=0;byte[] buffer=new byte[8192];
        try(var zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))){for(java.util.zip.ZipEntry entry;(entry=zip.getNextEntry())!=null;){
            if(++entries>10000)throw new IOException("RESOURCE_ARCHIVE_LIMIT");doc|=entry.getName().equals("word/document.xml");ppt|=entry.getName().equals("ppt/presentation.xml");
            for(int n;(n=zip.read(buffer))>=0;){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();expanded+=n;if(expanded>100L*1024*1024)throw new IOException("RESOURCE_ARCHIVE_LIMIT");}
        }}
        if(doc==ppt)throw new IOException("RESOURCE_OFFICE_FORMAT_AMBIGUOUS");return doc?"docx":"pptx";
    }
}
