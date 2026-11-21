package com.aid.previz.workspace;

import java.io.*;
import java.net.URI;
import java.util.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Collects bounded raster-image links only. XML external entities and DTDs are never resolved. */
public final class PrevizSvgReferences {
    private PrevizSvgReferences(){}
    public static List<String> read(byte[] bytes,String baseUrl)throws IOException{
        if(bytes==null||bytes.length>10*1024*1024)throw new IOException("RESOURCE_SVG_LIMIT");
        LinkedHashSet<String> links=new LinkedHashSet<>();
        try{
            SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING,true);
            var parser=factory.newSAXParser();parser.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");parser.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            parser.parse(new ByteArrayInputStream(bytes),new DefaultHandler(){int depth,elements;
                @Override public void startElement(String uri,String local,String name,Attributes attributes)throws SAXException{
                    if(++depth>64||++elements>50000||attributes.getLength()>256)throw new SAXException("RESOURCE_SVG_LIMIT");
                    if(!local.equals("image"))return;
                    String value=attributes.getValue("href");if(value==null)value=attributes.getValue("http://www.w3.org/1999/xlink","href");
                    if(value==null||value.startsWith("#")||value.startsWith("data:"))return;
                    try{URI url=baseUrl==null?URI.create(value):URI.create(baseUrl).resolve(value);
                        if(url.toString().length()>8192||!Set.of("http","https").contains(Objects.toString(url.getScheme(),"").toLowerCase(Locale.ROOT))||url.getHost()==null||url.getUserInfo()!=null)throw new IllegalArgumentException();
                        links.add(url.toASCIIString());if(links.size()>16)throw new SAXException("RESOURCE_SVG_LIMIT");
                    }catch(IllegalArgumentException invalid){throw new SAXException("RESOURCE_SVG_REFERENCE_INVALID");}
                }
                @Override public void endElement(String uri,String local,String name){depth--;}
                @Override public void error(SAXParseException error)throws SAXException{throw error;}
                @Override public void fatalError(SAXParseException error)throws SAXException{throw error;}
            });
        }catch(Exception invalid){throw new IOException(invalid.getMessage()!=null&&invalid.getMessage().startsWith("RESOURCE_")?invalid.getMessage():"RESOURCE_SVG_INVALID");}
        return List.copyOf(links);
    }
}
