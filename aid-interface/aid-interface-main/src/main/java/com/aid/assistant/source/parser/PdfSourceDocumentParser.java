package com.aid.assistant.source.parser;

import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.source.service.SourceDocumentParser;
import com.alibaba.fastjson2.JSONObject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 将有文本层的 PDF 解析为保留页码的有界分片。 */
@Component
public final class PdfSourceDocumentParser implements SourceDocumentParser {
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    private static final int MAX_PAGE_CHARS = 250_000;
    private final int maxPages;

    public PdfSourceDocumentParser(@Value("${aid.assistant.source.max-pdf-pages:500}") int maxPages) {
        if (maxPages < 1 || maxPages > 10_000) throw new IllegalArgumentException("Invalid PDF page limit");
        this.maxPages = maxPages;
    }

    @Override public String parserCode() { return "PDF_TEXT"; }
    @Override public String parserVersion() { return "1.0.0-pdfbox3.0.8"; }

    @Override
    public boolean supports(String extension, String mediaType, byte[] prefix) {
        return "pdf".equals(extension) && prefix != null && prefix.length >= 5
                && prefix[0] == '%' && prefix[1] == 'P' && prefix[2] == 'D'
                && prefix[3] == 'F' && prefix[4] == '-';
    }

    @Override
    public Discovery discover(byte[] bytes) {
        List<ParsedPart> all = parse(bytes, Set.of());
        int count = all.stream().mapToInt(ParsedPart::charCount).sum();
        return new Discovery(all.stream().map(part -> new PartDescriptor(
                part.partIndex(), part.partKind(), part.locatorJson())).toList(), count);
    }

    @Override
    public List<ParsedPart> parse(byte[] bytes, Set<Integer> indexes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_UPLOAD_BYTES) {
            throw SourceParserSupport.invalid("SOURCE_UPLOAD_LIMIT_EXCEEDED", "文件大小超限");
        }
        if (!supports("pdf", null, bytes)) {
            throw SourceParserSupport.invalid("SOURCE_PDF_MAGIC_INVALID", "PDF格式无效");
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent()) {
                throw SourceParserSupport.invalid("SOURCE_PDF_ENCRYPTED", "PDF禁止读取");
            }
            int pages = document.getNumberOfPages();
            if (pages < 1 || pages > maxPages) {
                throw SourceParserSupport.invalid("SOURCE_PDF_PAGE_LIMIT_EXCEEDED", "PDF页数超限");
            }
            if (indexes != null && indexes.stream().anyMatch(i -> i == null || i < 0 || i >= pages)) {
                throw SourceParserSupport.invalid("SOURCE_PART_INDEX_INVALID", "分片范围无效");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<ParsedPart> result = new ArrayList<>();
            int total = 0;
            boolean readable = false;
            for (int index = 0; index < pages; index++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Parser cancelled");
                if (indexes != null && !indexes.isEmpty() && !indexes.contains(index)) continue;
                stripper.setStartPage(index + 1);
                stripper.setEndPage(index + 1);
                LimitedWriter writer = new LimitedWriter(MAX_PAGE_CHARS);
                stripper.writeText(document, writer);
                String text = writer.value();
                SourceParserSupport.requireValidUtf16(text);
                readable |= !text.isBlank();
                total = Math.addExact(total, text.length());
                if (total > SourceParserSupport.MAX_EXTRACTED_CHARS) {
                    throw SourceParserSupport.invalid("SOURCE_TEXT_LIMIT_EXCEEDED", "提取文字超限");
                }
                JSONObject locator = new JSONObject();
                locator.put("format", "PDF");
                locator.put("page", index + 1);
                result.add(new ParsedPart(index, "PAGE", locator.toJSONString(), text,
                        SourceParserSupport.sha256(text), text.length(), SourceParserSupport.words(text)));
            }
            if ((indexes == null || indexes.isEmpty()) && !readable) {
                throw SourceParserSupport.invalid("SOURCE_PDF_OCR_REQUIRED", "扫描件需先识别");
            }
            return List.copyOf(result);
        } catch (AssistantServiceException known) {
            throw known;
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException protectedFile) {
            throw SourceParserSupport.invalid("SOURCE_PDF_ENCRYPTED", "PDF已加密");
        } catch (Exception malformed) {
            throw SourceParserSupport.invalid("SOURCE_PDF_MALFORMED", "PDF解析失败");
        }
    }

    private static final class LimitedWriter extends Writer {
        private final int limit;
        private final StringBuilder value = new StringBuilder();
        private LimitedWriter(int limit) { this.limit = limit; }
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            if (length > limit - value.length()) throw SourceParserSupport.invalid(
                    "SOURCE_PDF_PAGE_TEXT_LIMIT_EXCEEDED", "单页文字超限");
            value.append(chars, offset, length);
        }
        @Override public void flush() { }
        @Override public void close() { }
        private String value() { return value.toString(); }
    }
}
