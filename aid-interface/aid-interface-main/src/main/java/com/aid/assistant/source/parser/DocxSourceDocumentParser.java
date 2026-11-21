package com.aid.assistant.source.parser;

import com.aid.assistant.source.service.SourceDocumentParser;
import com.alibaba.fastjson2.JSONObject;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** DOCX parser with a bounded package audit before Apache POI reads the document. */
@Component
public final class DocxSourceDocumentParser implements SourceDocumentParser {
    public static final String CODE = "DOCX_POI";
    public static final String VERSION = "1.0.0-poi4.1";
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 100L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 10_000;
    private static final int MAX_PART_CHARS = 250_000;
    private final Limits limits;

    public DocxSourceDocumentParser() {
        this(new Limits(MAX_UPLOAD_BYTES, MAX_EXPANDED_BYTES, MAX_ZIP_ENTRIES,
                SourceParserSupport.MAX_EXTRACTED_CHARS));
    }

    DocxSourceDocumentParser(Limits limits) {
        this.limits = limits;
    }

    @Override
    public String parserCode() {
        return CODE;
    }

    @Override
    public String parserVersion() {
        return VERSION;
    }

    @Override
    public boolean supports(String extension, String mediaType, byte[] prefix) {
        return "docx".equals(extension) && prefix != null && prefix.length >= 4
                && prefix[0] == 'P' && prefix[1] == 'K' && prefix[2] == 3 && prefix[3] == 4;
    }

    @Override
    public Discovery discover(byte[] immutableBytes) {
        Parsed parsed = parseAll(immutableBytes);
        return new Discovery(parsed.parts().stream()
                .map(value -> new PartDescriptor(value.partIndex(), value.partKind(), value.locatorJson()))
                .toList(), parsed.charCount());
    }

    @Override
    public List<ParsedPart> parse(byte[] immutableBytes, Set<Integer> requestedPartIndexes) {
        Parsed parsed = parseAll(immutableBytes);
        if (requestedPartIndexes == null || requestedPartIndexes.isEmpty()) return parsed.parts();
        Set<Integer> requested = new LinkedHashSet<>(requestedPartIndexes);
        if (requested.stream().anyMatch(value -> value == null || value < 0 || value >= parsed.parts().size())) {
            throw SourceParserSupport.invalid("SOURCE_PART_INDEX_INVALID", "Requested source part is invalid");
        }
        return parsed.parts().stream().filter(value -> requested.contains(value.partIndex())).toList();
    }

    private Parsed parseAll(byte[] bytes) {
        auditPackage(bytes);
        List<ParsedPart> parts = new ArrayList<>();
        int charStart = 0;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<IBodyElement> body = document.getBodyElements();
            for (int bodyIndex = 0; bodyIndex < body.size(); bodyIndex++) {
                IBodyElement element = body.get(bodyIndex);
                BodyText value = bodyText(element);
                String content = value.content() + "\n";
                SourceParserSupport.requireValidUtf16(content);
                int segment = 0;
                int offset = 0;
                while (offset < content.length()) {
                    int end = Math.min(content.length(), offset + MAX_PART_CHARS);
                    if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) end--;
                    if (end <= offset) throw SourceParserSupport.invalid(
                            "SOURCE_TEXT_INVALID_UTF16", "DOCX contains an invalid text boundary");
                    String partContent = content.substring(offset, end);
                    int partIndex = parts.size();
                    JSONObject locator = new JSONObject();
                    locator.put("format", "DOCX");
                    locator.put("bodyIndex", bodyIndex);
                    locator.put("bodyKind", value.kind());
                    locator.put("segment", segment);
                    locator.put("charStart", charStart);
                    locator.put("charEnd", charStart + partContent.length());
                    if (value.style() != null) locator.put("style", value.style());
                    parts.add(new ParsedPart(partIndex, value.kind(), locator.toJSONString(), partContent,
                            SourceParserSupport.sha256(partContent), partContent.length(),
                            SourceParserSupport.words(partContent)));
                    charStart += partContent.length();
                    offset = end;
                    segment++;
                }
                if (parts.size() > limits.maxEntries()) {
                    throw SourceParserSupport.invalid("SOURCE_PART_LIMIT_EXCEEDED", "DOCX contains too many parts");
                }
            }
        } catch (com.aid.assistant.error.AssistantServiceException known) {
            throw known;
        } catch (Exception invalid) {
            throw SourceParserSupport.invalid("SOURCE_DOCX_MALFORMED", "DOCX container or document XML is malformed");
        }
        if (parts.isEmpty() || parts.stream().allMatch(value -> value.content().isBlank())) {
            throw SourceParserSupport.invalid("SOURCE_TEXT_EMPTY", "DOCX contains no readable text");
        }
        if (charStart > limits.maxExtractedChars()) {
            throw SourceParserSupport.invalid("SOURCE_TEXT_LIMIT_EXCEEDED", "Extracted DOCX text exceeds the supported limit");
        }
        return new Parsed(List.copyOf(parts), charStart);
    }

    private BodyText bodyText(IBodyElement element) {
        if (element instanceof XWPFParagraph paragraph) {
            String style = safeStyle(paragraph.getStyle());
            String kind = style != null && style.toLowerCase(Locale.ROOT).startsWith("heading")
                    ? "SECTION" : "PARAGRAPH";
            return new BodyText(kind, paragraph.getText() == null ? "" : paragraph.getText(), style);
        }
        if (element instanceof XWPFTable table) {
            StringBuilder text = new StringBuilder();
            List<XWPFTableRow> rows = table.getRows();
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                List<XWPFTableCell> cells = rows.get(rowIndex).getTableCells();
                for (int cellIndex = 0; cellIndex < cells.size(); cellIndex++) {
                    if (cellIndex > 0) text.append('\t');
                    String cell = cells.get(cellIndex).getText();
                    if (cell != null) text.append(cell);
                }
                if (rowIndex + 1 < rows.size()) text.append('\n');
            }
            return new BodyText("TABLE", text.toString(), null);
        }
        throw SourceParserSupport.invalid("SOURCE_DOCX_BODY_UNSUPPORTED", "DOCX contains an unsupported body element");
    }

    private String safeStyle(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), 100));
    }

    private void auditPackage(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw SourceParserSupport.invalid("SOURCE_FILE_EMPTY", "Source file is empty");
        }
        if (bytes.length > limits.maxUploadBytes()) {
            throw SourceParserSupport.invalid("SOURCE_UPLOAD_LIMIT_EXCEEDED", "Source upload exceeds 10 MiB");
        }
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            throw SourceParserSupport.invalid("SOURCE_DOCX_MAGIC_INVALID", "DOCX package signature is invalid");
        }
        boolean contentTypes = false;
        boolean documentXml = false;
        long expanded = 0;
        int entries = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > limits.maxEntries()) {
                    throw SourceParserSupport.invalid("SOURCE_ZIP_ENTRY_LIMIT_EXCEEDED", "DOCX contains too many ZIP entries");
                }
                String name = normalizeEntry(entry.getName());
                if (!names.add(name)) {
                    throw SourceParserSupport.invalid("SOURCE_ZIP_DUPLICATE_ENTRY", "DOCX contains duplicate ZIP entries");
                }
                contentTypes |= "[Content_Types].xml".equals(name);
                documentXml |= "word/document.xml".equals(name);
                ByteArrayOutputStream xml = isXml(name) ? new ByteArrayOutputStream() : null;
                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    expanded = Math.addExact(expanded, read);
                    if (expanded > limits.maxExpandedBytes()) {
                        throw SourceParserSupport.invalid("SOURCE_ZIP_EXPANSION_LIMIT_EXCEEDED",
                                "DOCX expanded content exceeds 100 MiB");
                    }
                    if (xml != null) xml.write(buffer, 0, read);
                }
                if (xml != null) auditXml(name, xml.toByteArray());
                zip.closeEntry();
            }
        } catch (com.aid.assistant.error.AssistantServiceException known) {
            throw known;
        } catch (Exception invalid) {
            throw SourceParserSupport.invalid("SOURCE_DOCX_MALFORMED", "DOCX ZIP container is malformed");
        }
        if (!contentTypes || !documentXml) {
            throw SourceParserSupport.invalid("SOURCE_DOCX_MALFORMED", "DOCX package is missing required entries");
        }
    }

    private String normalizeEntry(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 512 || raw.indexOf('\\') >= 0
                || raw.startsWith("/") || raw.contains("//")) {
            throw SourceParserSupport.invalid("SOURCE_ZIP_ENTRY_INVALID", "DOCX contains an invalid ZIP entry name");
        }
        String[] segments = raw.split("/");
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw SourceParserSupport.invalid("SOURCE_ZIP_ENTRY_INVALID", "DOCX contains an invalid ZIP entry path");
            }
        }
        return raw;
    }

    private boolean isXml(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xml") || lower.endsWith(".rels");
    }

    private void auditXml(String name, byte[] bytes) {
        // Validate every XML entry with a secure SAX reader, including UTF-16 documents.
        try {
            javax.xml.parsers.SAXParserFactory sax = javax.xml.parsers.SAXParserFactory.newInstance();
            sax.setNamespaceAware(true);
            sax.setXIncludeAware(false);
            sax.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            sax.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            sax.setFeature("http://xml.org/sax/features/external-general-entities", false);
            sax.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            org.xml.sax.XMLReader reader = sax.newSAXParser().getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            reader.setContentHandler(new org.xml.sax.helpers.DefaultHandler());
            reader.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            });
            reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
        } catch (Exception invalid) {
            throw SourceParserSupport.invalid("SOURCE_XML_UNSAFE_OR_MALFORMED", "Unsafe or malformed XML");
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".rels")) return;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            InputSource source = new InputSource(new ByteArrayInputStream(bytes));
            Document document = factory.newDocumentBuilder().parse(source);
            NodeList relationships = document.getElementsByTagNameNS("*", "Relationship");
            for (int index = 0; index < relationships.getLength(); index++) {
                Node relationship = relationships.item(index);
                Node mode = relationship.getAttributes().getNamedItem("TargetMode");
                if (mode != null && "External".equalsIgnoreCase(mode.getNodeValue())) {
                    throw SourceParserSupport.invalid("SOURCE_DOCX_EXTERNAL_RELATIONSHIP_FORBIDDEN",
                            "DOCX contains an external relationship");
                }
            }
        } catch (com.aid.assistant.error.AssistantServiceException known) {
            throw known;
        } catch (Exception invalid) {
            throw SourceParserSupport.invalid("SOURCE_DOCX_MALFORMED", "DOCX relationship XML is malformed");
        }
    }

    private record BodyText(String kind, String content, String style) {
    }

    private record Parsed(List<ParsedPart> parts, int charCount) {
    }

    record Limits(int maxUploadBytes, long maxExpandedBytes, int maxEntries, int maxExtractedChars) {
        Limits {
            if (maxUploadBytes < 1 || maxExpandedBytes < 1 || maxEntries < 2 || maxExtractedChars < 1) {
                throw new IllegalArgumentException("Invalid DOCX parser limits");
            }
        }
    }
}
