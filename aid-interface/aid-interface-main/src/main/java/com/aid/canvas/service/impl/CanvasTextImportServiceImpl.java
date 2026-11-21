package com.aid.canvas.service.impl;

import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasPlainText;
import com.aid.canvas.service.CanvasTextImportService;
import com.aid.common.exception.ServiceException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** 文件只在本次请求内解析，不持久化原件；超限直接拒绝。 */
@Service
public class CanvasTextImportServiceImpl implements CanvasTextImportService {
    private final CanvasAccessService accessService;
    @Value("${canvas.text-import.max-file-bytes:10485760}") private long maxFileBytes;
    @Value("${canvas.text-import.max-pages:100}") private int maxPages;
    @Value("${canvas.text-import.max-content-chars:65536}") private int maxContentChars;

    public CanvasTextImportServiceImpl(CanvasAccessService accessService) {
        this.accessService = accessService;
    }

    @Override
    public Map<String, Object> parse(Long workspaceId, MultipartFile file, Long userId) {
        accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        if (file == null || file.isEmpty()) throw new ServiceException("导入文件不能为空");
        if (maxFileBytes <= 0 || maxPages <= 0 || maxContentChars <= 0) throw new ServiceException("文本导入限制配置无效");
        if (file.getSize() > maxFileBytes) throw new ServiceException("导入文件超过大小限制");
        String name = file.getOriginalFilename();
        String suffix = name == null || !name.contains(".") ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!"txt".equals(suffix) && !"md".equals(suffix) && !"pdf".equals(suffix) && !"docx".equals(suffix)) {
            throw new ServiceException("只支持 TXT、MD、文本型 PDF 和 DOCX；不支持 DOC/OCR");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > maxFileBytes) throw new ServiceException("导入文件超过大小限制");
            String text = switch (suffix) {
                case "txt", "md" -> strictUtf8(bytes);
                case "pdf" -> extractPdf(bytes);
                case "docx" -> extractDocx(bytes);
                default -> throw new ServiceException("文件格式不支持");
            };
            String result = CanvasPlainText.normalize(text, "md".equals(suffix) ? "MARKDOWN" : "TEXT");
            if (result.isBlank()) throw new ServiceException("文件不包含可导入文字；不支持 OCR");
            if (result.length() > maxContentChars || result.getBytes(StandardCharsets.UTF_8).length > 128 * 1024) {
                throw new ServiceException("导入文字超过节点正文限制");
            }
            return Map.of("textContent", result, "textFormat", "TEXT", "characterCount", result.length());
        } catch (ServiceException error) {
            throw error;
        } catch (Exception error) {
            throw new ServiceException("文件解析失败或内容不受支持");
        }
    }

    private String extractPdf(byte[] bytes) throws IOException {
        if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new ServiceException("PDF 文件内容无效");
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.getNumberOfPages() < 1 || document.getNumberOfPages() > maxPages) {
                throw new ServiceException("PDF 页数超过限制");
            }
            LimitedTextWriter writer = new LimitedTextWriter(maxContentChars);
            try {
                new PDFTextStripper().writeText(document, writer);
            } catch (TextLimitExceeded tooLarge) {
                throw new ServiceException("导入文字超过节点正文限制");
            }
            return writer.toString();
        }
    }

    private String extractDocx(byte[] bytes) throws IOException {
        DocxParts parts = inspectZip(bytes);
        if (parts.document() == null) throw new ServiceException("DOCX 缺少正文");
        Integer pages = declaredPages(parts.appProperties());
        if (pages != null && pages > maxPages) throw new ServiceException("DOCX 页数超过限制");
        DocxTextHandler handler = new DocxTextHandler(maxContentChars, maxPages);
        parseXml(parts.document(), handler);
        return handler.text();
    }

    private DocxParts inspectZip(byte[] bytes) throws IOException {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw new ServiceException("DOCX 文件内容无效");
        long expanded = 0;
        int count = 0;
        byte[] document = null;
        byte[] properties = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 1000) throw new ServiceException("DOCX 压缩条目过多");
                String name = entry.getName();
                boolean main = "word/document.xml".equals(name);
                boolean app = "docProps/app.xml".equals(name);
                ByteArrayOutputStream selected = main || app ? new ByteArrayOutputStream() : null;
                long entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expanded += read;
                    entryBytes += read;
                    if (expanded > 16L * 1024 * 1024 || entryBytes > 8L * 1024 * 1024) {
                        throw new ServiceException("DOCX 解压内容超过限制");
                    }
                    if (selected != null) {
                        if (entryBytes > (main ? 4L * 1024 * 1024 : 64L * 1024)) {
                            throw new ServiceException("DOCX 正文结构超过限制");
                        }
                        selected.write(buffer, 0, read);
                    }
                }
                if (main) document = selected.toByteArray();
                if (app) properties = selected.toByteArray();
                zip.closeEntry();
            }
        }
        if (count == 0) throw new ServiceException("DOCX 文件内容无效");
        return new DocxParts(document, properties);
    }

    private Integer declaredPages(byte[] appProperties) {
        if (appProperties == null) return null;
        final StringBuilder pageText = new StringBuilder();
        try {
            parseXml(appProperties, new DefaultHandler() {
                private boolean inPages;
                @Override public void startElement(String uri, String localName, String qName, Attributes attributes) {
                    inPages = "Pages".equals(localName) || "Pages".equals(qName);
                }
                @Override public void characters(char[] ch, int start, int length) {
                    if (inPages && pageText.length() < 16) pageText.append(ch, start, Math.min(length, 16 - pageText.length()));
                }
                @Override public void endElement(String uri, String localName, String qName) { inPages = false; }
            });
            return pageText.isEmpty() ? null : Integer.parseInt(pageText.toString().trim());
        } catch (NumberFormatException invalid) {
            throw new ServiceException("DOCX 页数元数据无效");
        }
    }

    private static void parseXml(byte[] xml, DefaultHandler handler) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.newSAXParser().parse(new ByteArrayInputStream(xml), handler);
        } catch (SAXException error) {
            if (error.getCause() instanceof ServiceException serviceError) throw serviceError;
            throw new ServiceException("DOCX XML 内容无效或超过限制");
        } catch (ServiceException error) {
            throw error;
        } catch (Exception error) {
            throw new ServiceException("DOCX XML 内容无效");
        }
    }

    private record DocxParts(byte[] document, byte[] appProperties) { }

    private static final class DocxTextHandler extends DefaultHandler {
        private final int maxChars;
        private final int maxPages;
        private final StringBuilder out = new StringBuilder();
        private boolean inText;
        private int pageBreaks;
        private int paragraphs;

        private DocxTextHandler(int maxChars, int maxPages) {
            this.maxChars = maxChars;
            this.maxPages = maxPages;
        }

        @Override public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if ("t".equals(localName)) inText = true;
            else if ("tab".equals(localName)) append("\t");
            else if ("lastRenderedPageBreak".equals(localName)) pageBreak();
            else if ("br".equals(localName)) {
                if ("page".equals(attributes.getValue(uri, "type")) || "page".equals(attributes.getValue("w:type"))) pageBreak();
                append("\n");
            }
        }

        @Override public void characters(char[] chars, int start, int length) {
            if (inText) append(new String(chars, start, length));
        }

        @Override public void endElement(String uri, String localName, String qName) {
            if ("t".equals(localName)) inText = false;
            else if ("p".equals(localName)) {
                if (++paragraphs > maxPages * 100) throw new ServiceException("DOCX 内容结构超过限制");
                append("\n");
            } else if ("tc".equals(localName)) append("\t");
        }

        private void pageBreak() {
            if (++pageBreaks >= maxPages) throw new ServiceException("DOCX 页数超过限制");
        }

        private void append(String value) {
            if ((long) out.length() + value.length() > maxChars) throw new ServiceException("导入文字超过节点正文限制");
            out.append(value);
        }

        private String text() { return out.toString(); }
    }

    private static final class TextLimitExceeded extends IOException { }

    private static final class LimitedTextWriter extends Writer {
        private final int maxChars;
        private final StringBuilder out = new StringBuilder();
        private LimitedTextWriter(int maxChars) { this.maxChars = maxChars; }
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            if ((long) out.length() + length > maxChars) throw new TextLimitExceeded();
            out.append(chars, offset, length);
        }
        @Override public void write(String text, int offset, int length) throws IOException {
            if ((long) out.length() + length > maxChars) throw new TextLimitExceeded();
            out.append(text, offset, offset + length);
        }
        @Override public void flush() { }
        @Override public void close() { }
        @Override public String toString() { return out.toString(); }
    }

    private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        ByteBuffer input = ByteBuffer.wrap(bytes);
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf) input.position(3);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(input).toString();
    }
}
