package com.aid.assistant.source.service.impl;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.domain.AidRuntimeSourceChunk;
import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceChunkMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceRevisionMapper;
import com.aid.assistant.creative.service.CreativeTextChunker;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.service.AssistantCoreService;
import com.aid.assistant.source.domain.AidAssistantSourceImport;
import com.aid.assistant.source.domain.AidAssistantSourceImportPart;
import com.aid.assistant.source.dto.SourceImportRequests;
import com.aid.assistant.source.mapper.AidAssistantSourceImportMapper;
import com.aid.assistant.source.mapper.AidAssistantSourceImportPartMapper;
import com.aid.assistant.source.service.AssistantSourceBlobStore;
import com.aid.assistant.source.service.AssistantSourceImportService;
import com.aid.assistant.source.service.SourceDocumentParser;
import com.aid.assistant.source.service.SourceDocumentParserRegistry;
import com.aid.assistant.source.service.SourceParserExecutor;
import com.aid.assistant.source.vo.SourceImportViews;
import com.aid.runtime.assistant.correction.AssistantDecisionCorrectionCommandNamespace;
import com.aid.runtime.domain.AidRuntimeCommand;
import com.aid.runtime.mapper.AidRuntimeCommandMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 持久管理文档上传、分片解析、取消与源文最终化。 */
@Slf4j
@Service
public class AssistantSourceImportServiceImpl implements AssistantSourceImportService {
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    private static final int MAX_CHAR_COUNT = 5_000_000;
    private static final int MAX_PART_COUNT = 10_000;
    private static final int MAX_ACTIVE_IMPORTS = 20;
    private static final int MAX_ATTEMPTS = 3;
    private static final long LEASE_MILLIS = 120_000;
    private static final String UPLOAD = "SOURCE_IMPORT_UPLOAD";
    private static final String ADVANCE = "SOURCE_IMPORT_ADVANCE";
    private static final String CANCEL = "SOURCE_IMPORT_CANCEL";
    @Resource private AssistantCoreService core;
    @Resource private AidAssistantSourceImportMapper documents;
    @Resource private AidAssistantSourceImportPartMapper parts;
    @Resource private AidRuntimeCommandMapper commands;
    @Resource private AidRuntimeSourceRevisionMapper revisions;
    @Resource private AidRuntimeSourceChunkMapper chunks;
    @Resource private AssistantSourceBlobStore blobs;
    @Resource private SourceDocumentParserRegistry parsers;
    @Resource private SourceParserExecutor parserExecutor;
    @Resource private com.aid.assistant.creative.series.FlowScreenplaySourcePolicy flowScreenplaySources;
    @Resource private TransactionTemplate transactions;
    @Resource private com.aid.assistant.creative.service.CreativeSourceService sourceReader;

    @Override
    public SourceImportViews.Command upload(Long userId, SourceImportRequests.Upload request) {
        Long sessionId = id(request.getSessionId());
        core.authorizeSession(userId, sessionId, false);
        byte[] bytes;
        try (InputStream input = request.getFile().getInputStream()) {
            bytes = input.readNBytes(MAX_UPLOAD_BYTES + 1);
        } catch (Exception failed) { throw failure(422, "SOURCE_UPLOAD_READ_FAILED"); }
        if (bytes.length < 1 || bytes.length > MAX_UPLOAD_BYTES) throw failure(422, "SOURCE_UPLOAD_LIMIT_EXCEEDED");
        String name = safeName(request.getFile().getOriginalFilename());
        String mime = Objects.toString(request.getFile().getContentType(), "application/octet-stream");
        if (mime.length() > 100 || mime.chars().anyMatch(Character::isISOControl)) throw failure(422, "SOURCE_MEDIA_TYPE_INVALID");
        SourceDocumentParser parser = parsers.require(name, mime, bytes);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("sessionId", sessionId); identity.put("name", name); identity.put("mediaType", mime);
        identity.put("byteHash", byteHash(bytes)); identity.put("byteCount", bytes.length);
        identity.put("parserCode", parser.parserCode()); identity.put("parserVersion", parser.parserVersion());
        String digest = digest(UPLOAD, identity);
        AidRuntimeCommand existing = findCommand(userId, sessionId, request.getClientRequestId());
        if (existing != null) return commandView(requireCommand(existing, UPLOAD, digest), userId, sessionId);
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        String locator = blobs.store(bytes, extension);
        UploadResult outcome;
        try {
            outcome = transactions.execute(status -> {
                AidAssistantSession session = core.authorizeSession(userId, sessionId, true);
                AidRuntimeCommand winner = findCommand(userId, sessionId, request.getClientRequestId());
                if (winner != null) return new UploadResult(requireCommand(winner, UPLOAD, digest), false);
                long count = documents.selectCount(Wrappers.<AidAssistantSourceImport>lambdaQuery()
                        .eq(AidAssistantSourceImport::getUserId, userId).eq(AidAssistantSourceImport::getSessionId, sessionId)
                        .eq(AidAssistantSourceImport::getDelFlag, "0")
                        .notIn(AidAssistantSourceImport::getStatus, "FINALIZED", "CANCELLED"));
                if (count >= MAX_ACTIVE_IMPORTS) throw failure(422, "SOURCE_IMPORT_ACTIVE_LIMIT");
                AidAssistantSourceImport document = new AidAssistantSourceImport();
                document.setUserId(userId); document.setSessionId(sessionId);
                document.setProjectId(session.getProjectId() == null ? 0L : session.getProjectId());
                document.setOriginalName(name); document.setMediaType(mime);
                document.setByteCount((long) bytes.length); document.setByteHash(byteHash(bytes));
                document.setStorageLocator(locator); document.setParserCode(parser.parserCode());
                document.setParserVersion(parser.parserVersion()); document.setStatus("UPLOADED");
                document.setStateRevision(0L); document.setFencingToken(0L); document.setPartCount(0);
                document.setReadyPartCount(0); document.setDelFlag("0"); audit(document, userId, true);
                documents.insert(document);
                AidRuntimeCommand command = createCommand(userId, sessionId, request.getClientRequestId(), UPLOAD, digest, document.getId());
                complete(command, "COMPLETED", null);
                return new UploadResult(command, true);
            });
            if (outcome == null) throw failure(500, "SOURCE_UPLOAD_RESULT_MISSING");
        } catch (RuntimeException failed) { blobs.deleteQuietly(locator); throw failed; }
        if (!outcome.newBlobUsed()) blobs.deleteQuietly(locator);
        return commandView(outcome.command(), userId, sessionId);
    }

    @Override
    public SourceImportViews.Page page(Long userId, SourceImportRequests.Page request) {
        Long sessionId = id(request.getSessionId()); core.authorizeSession(userId, sessionId, false);
        int limit = request.getLimit() == null ? 20 : request.getLimit();
        if (limit < 1 || limit > 50) throw failure(422, "SOURCE_PAGE_INVALID");
        var query = Wrappers.<AidAssistantSourceImport>lambdaQuery()
                .eq(AidAssistantSourceImport::getUserId, userId).eq(AidAssistantSourceImport::getSessionId, sessionId)
                .eq(AidAssistantSourceImport::getDelFlag, "0");
        if (request.getAfterDocumentId() != null) query.gt(AidAssistantSourceImport::getId, id(request.getAfterDocumentId()));
        List<AidAssistantSourceImport> rows = documents.selectList(query.orderByAsc(AidAssistantSourceImport::getId)
                .last("LIMIT " + (limit + 1)));
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        Map<Long, Integer> failedCounts = new LinkedHashMap<>();
        if (!rows.isEmpty()) {
            List<Map<String, Object>> grouped = parts.selectMaps(new QueryWrapper<AidAssistantSourceImportPart>()
                    .select("import_id", "COUNT(*) AS failures").in("import_id", rows.stream().map(AidAssistantSourceImport::getId).toList())
                    .eq("status", "FAILED").eq("del_flag", "0").groupBy("import_id"));
            for (Map<String, Object> value : grouped) failedCounts.put(((Number) value.get("import_id")).longValue(), ((Number) value.get("failures")).intValue());
        }
        List<SourceImportViews.Summary> views = rows.stream().map(row -> summary(row, failedCounts.getOrDefault(row.getId(), 0))).toList();
        return new SourceImportViews.Page("assistant-source-import-page/v1", views,
                more ? String.valueOf(rows.get(rows.size() - 1).getId()) : null, !more);
    }

    @Override
    public SourceImportViews.Detail detail(Long userId, SourceImportRequests.Detail request) {
        Long sessionId = id(request.getSessionId()); core.authorizeSession(userId, sessionId, false);
        return detailView(requireDocument(userId, sessionId, id(request.getDocumentId()), false),
                request.getAfterPartIndex() == null ? -1 : request.getAfterPartIndex(), request.getPartLimit() == null ? 100 : request.getPartLimit());
    }

    @Override
    public SourceImportViews.Lookup lookup(Long userId, SourceImportRequests.Lookup request) {
        Long sessionId = id(request.getSessionId()); core.authorizeSession(userId, sessionId, false);
        AidRuntimeCommand command = findCommand(userId, sessionId, request.getClientRequestId());
        if (command == null) return new SourceImportViews.Lookup("assistant-source-import-lookup/v1",
                request.getClientRequestId(), "NOT_OBSERVED", null);
        if (!Set.of(UPLOAD, ADVANCE, CANCEL).contains(command.getCommandType())) throw failure(409, "SOURCE_COMMAND_REUSED");
        return new SourceImportViews.Lookup("assistant-source-import-lookup/v1", request.getClientRequestId(),
                "OBSERVED", commandView(command, userId, sessionId));
    }

    @Override
    public com.aid.assistant.creative.vo.CreativeViews.SourceChunkPage content(Long userId, SourceImportRequests.Content request) {
        Long sessionId = id(request.getSessionId()); core.authorizeSession(userId, sessionId, false);
        AidAssistantSourceImport document = requireDocument(userId, sessionId, id(request.getDocumentId()), false);
        if (!"FINALIZED".equals(document.getStatus()) || document.getSourceRevisionId() == null) throw failure(409, "SOURCE_NOT_FINALIZED");
        JSONObject canonical = new JSONObject(); canonical.put("documentId", request.getDocumentId()); canonical.put("contentHash", document.getContentHash());
        JSONObject ref = new JSONObject(); ref.put("kind", "DOCUMENT"); ref.put("canonicalRef", canonical);
        ref.put("contentRef", document.getSourceRevision()); ref.put("access", "READ");
        JSONObject manifest = new JSONObject(); manifest.put("schemaVersion", "assistant-context-manifest/v1"); manifest.put("refs", List.of(ref));
        com.aid.assistant.creative.dto.CreativeRequests.SourceChunkRead read = new com.aid.assistant.creative.dto.CreativeRequests.SourceChunkRead();
        read.setSourceRevisionId(document.getSourceRevisionId()); read.setAfterChunkIndex(request.getAfterChunkIndex()); read.setLimit(request.getLimit());
        return sourceReader.read(userId, read, manifest.toJSONString());
    }

    @Override
    public SourceImportViews.Command advance(Long userId, SourceImportRequests.Advance request) {
        Long sessionId = id(request.getSessionId()); Long documentId = id(request.getDocumentId());
        String digest = digest(ADVANCE, request);
        Claim claim = transactions.execute(status -> claim(userId, sessionId, documentId, request, digest));
        if (claim == null) throw failure(500, "SOURCE_CLAIM_MISSING");
        if (claim.replay()) return commandView(claim.command(), userId, sessionId);
        try {
            if ("FINALIZING".equals(claim.document().getStatus())) {
                finalizeSource(claim);
            } else {
                byte[] bytes = blobs.read(claim.document().getStorageLocator(), claim.document().getByteCount());
                if (!Objects.equals(byteHash(bytes), claim.document().getByteHash())) throw failure(409, "SOURCE_BYTES_CHANGED");
                SourceDocumentParser parser = parsers.require(claim.document().getOriginalName(), claim.document().getMediaType(), bytes);
                if (!Objects.equals(parser.parserCode(), claim.document().getParserCode())
                        || !Objects.equals(parser.parserVersion(), claim.document().getParserVersion())) {
                    throw failure(409, "SOURCE_PARSER_VERSION_CHANGED");
                }
                if ("DISCOVERING".equals(claim.document().getStatus())) {
                    SourceDocumentParser.Discovery discovery = parserExecutor.parse(() -> parser.discover(bytes));
                    transactions.executeWithoutResult(status -> persistDiscovery(claim, discovery));
                } else {
                    List<SourceDocumentParser.ParsedPart> parsed = parserExecutor.parse(() -> parser.parse(bytes, claim.indexes()));
                    boolean finalize = Boolean.TRUE.equals(transactions.execute(status -> persistParts(claim, parsed)));
                    if (finalize) finalizeSource(claim);
                }
            }
        } catch (RuntimeException failed) {
            markFailed(claim, failed instanceof AssistantServiceException known ? known.getErrorCode() : "SOURCE_IMPORT_FAILED");
        }
        AidAssistantSourceImport current = requireDocument(userId, sessionId, documentId, false);
        if ("FINALIZED".equals(current.getStatus())) blobs.deleteQuietly(current.getStorageLocator());
        return commandView(commands.selectById(claim.command().getId()), userId, sessionId);
    }

    private Claim claim(Long userId, Long sessionId, Long documentId, SourceImportRequests.Advance request, String digest) {
        AidAssistantSession session = core.authorizeSession(userId, sessionId, true);
        AidAssistantSourceImport document = requireDocument(userId, sessionId, documentId, true);
        AidRuntimeCommand command = findCommand(userId, sessionId, request.getClientRequestId());
        if (command != null) {
            requireCommand(command, ADVANCE, digest);
            if (!"PROCESSING".equals(command.getStatus())) return new Claim(document, command, session, null, Set.of(), true);
        } else {
            requireRevision(document, request.getExpectedStateRevision());
            command = createCommand(userId, sessionId, request.getClientRequestId(), ADVANCE, digest, documentId);
            JSONObject receipt = JSON.parseObject(command.getResultJson());
            receipt.put("advanceRequest", JSON.parseObject(JSON.toJSONString(request)));
            command.setResultJson(receipt.toJSONString()); commands.updateById(command);
        }
        if (List.of("FINALIZED", "CANCELLED").contains(document.getStatus())) {
            complete(command, "COMPLETED", null);
            return new Claim(document, command, session, null, Set.of(), true);
        }
        Date now = new Date();
        if (document.getLeaseUntil() != null && document.getLeaseUntil().after(now)) throw failure(409, "SOURCE_IMPORT_BUSY");
        boolean discovery = document.getPartCount() == 0;
        boolean finalize = !discovery && Objects.equals(document.getReadyPartCount(), document.getPartCount());
        List<AidAssistantSourceImportPart> all = discovery ? List.of() : parts.selectByImportIdForUpdate(documentId);
        Set<Integer> selected = new LinkedHashSet<>();
        int max = request.getMaxParts() == null ? 20 : request.getMaxParts();
        if (max < 1 || max > 20 || request.getPartIndexes() != null && request.getPartIndexes().size() > 20) throw failure(422, "SOURCE_PART_LIMIT_INVALID");
        if (!discovery && !finalize) {
            Set<Integer> explicit = new LinkedHashSet<>(request.getPartIndexes() == null ? List.of() : request.getPartIndexes());
            if (explicit.size() != (request.getPartIndexes() == null ? 0 : request.getPartIndexes().size())) throw failure(422, "SOURCE_PART_DUPLICATE");
            int partCount = document.getPartCount();
            if (explicit.stream().anyMatch(i -> i == null || i < 0 || i >= partCount)) throw failure(422, "SOURCE_PART_INDEX_INVALID");
            for (AidAssistantSourceImportPart part : all) {
                if (selected.size() >= max) break;
                if ((explicit.isEmpty() || explicit.contains(part.getPartIndex())) && !"READY".equals(part.getStatus())
                        && part.getAttemptCount() < MAX_ATTEMPTS) selected.add(part.getPartIndex());
            }
            if (selected.isEmpty()) throw failure(409, "SOURCE_RETRY_LIMIT_REACHED");
        }
        if (discovery && document.getFencingToken() >= MAX_ATTEMPTS) throw failure(409, "SOURCE_RETRY_LIMIT_REACHED");
        String owner = UUID.randomUUID().toString();
        Date until = new Date(now.getTime() + LEASE_MILLIS);
        String targetStatus = discovery ? "DISCOVERING" : finalize ? "FINALIZING" : "PARSING";
        if (documents.claim(documentId, document.getStateRevision(), document.getStatus(), targetStatus, owner, until, now, String.valueOf(userId)) != 1) throw failure(409, "SOURCE_IMPORT_BUSY");
        for (AidAssistantSourceImportPart part : all) {
            if (selected.contains(part.getPartIndex()) && parts.claim(part.getId(), part.getStateRevision(),
                    part.getStatus(), "PARSING", owner, until, now, String.valueOf(userId)) != 1) throw failure(409, "SOURCE_PART_BUSY");
        }
        // A new durable command supersedes an expired previous claim; it must never stay PROCESSING forever.
        commands.update(null, Wrappers.<AidRuntimeCommand>lambdaUpdate().eq(AidRuntimeCommand::getActorId, userId)
                .eq(AidRuntimeCommand::getSessionId, sessionId).eq(AidRuntimeCommand::getCommandType, ADVANCE)
                .eq(AidRuntimeCommand::getStatus, "PROCESSING").ne(AidRuntimeCommand::getId, command.getId())
                .apply("JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.documentId'))={0}", String.valueOf(documentId))
                .set(AidRuntimeCommand::getStatus, "FAILED").set(AidRuntimeCommand::getErrorCode, "SOURCE_IMPORT_SUPERSEDED")
                .set(AidRuntimeCommand::getUpdateBy, String.valueOf(userId)).set(AidRuntimeCommand::getUpdateTime, now));
        document = documents.selectById(documentId);
        return new Claim(document, command, session, owner, Set.copyOf(selected), false);
    }

    private void persistDiscovery(Claim claim, SourceDocumentParser.Discovery discovery) {
        AidAssistantSourceImport document = lockClaim(claim);
        if (discovery == null || discovery.parts().isEmpty() || discovery.parts().size() > MAX_PART_COUNT
                || discovery.extractedCharCount() < 1 || discovery.extractedCharCount() > MAX_CHAR_COUNT) throw failure(422, "SOURCE_DISCOVERY_INVALID");
        int index = 0;
        for (SourceDocumentParser.PartDescriptor descriptor : discovery.parts()) {
            if (descriptor.partIndex() != index++ || descriptor.partKind() == null || descriptor.partKind().length() > 20
                    || descriptor.locatorJson() == null || descriptor.locatorJson().length() > 4000) throw failure(422, "SOURCE_PART_DESCRIPTOR_INVALID");
            JSON.parseObject(descriptor.locatorJson());
            AidAssistantSourceImportPart part = new AidAssistantSourceImportPart();
            part.setImportId(document.getId()); part.setPartIndex(descriptor.partIndex());
            part.setPartKind(descriptor.partKind()); part.setLocatorJson(descriptor.locatorJson());
            part.setStatus("PENDING"); part.setAttemptCount(0); part.setStateRevision(0L);
            part.setFencingToken(0L); part.setDelFlag("0"); audit(part, document.getUserId(), true); parts.insert(part);
        }
        document.setPartCount(discovery.parts().size()); document.setStatus("PARTIAL");
        release(document); documents.updateById(document);
        complete(claim.command(), "COMPLETED", null);
    }

    private boolean persistParts(Claim claim, List<SourceDocumentParser.ParsedPart> parsed) {
        AidAssistantSourceImport document = lockClaim(claim);
        if (parsed == null || parsed.size() != claim.indexes().size()) throw failure(422, "SOURCE_PART_RESULT_INVALID");
        Map<Integer, SourceDocumentParser.ParsedPart> values = new LinkedHashMap<>();
        for (SourceDocumentParser.ParsedPart value : parsed) {
            if (!claim.indexes().contains(value.partIndex()) || values.put(value.partIndex(), value) != null
                    || value.content() == null || value.content().length() > 250_000
                    || value.charCount() != value.content().length() || !Objects.equals(SecureUtil.sha256(value.content()), value.contentHash())) throw failure(422, "SOURCE_PART_RESULT_INVALID");
        }
        List<AidAssistantSourceImportPart> all = parts.selectByImportIdForUpdate(document.getId());
        for (AidAssistantSourceImportPart part : all) {
            if (!claim.indexes().contains(part.getPartIndex())) continue;
            SourceDocumentParser.ParsedPart value = values.get(part.getPartIndex());
            if (!"PARSING".equals(part.getStatus()) || !Objects.equals(claim.owner(), part.getLeaseOwner())
                    || !Objects.equals(part.getPartKind(), value.partKind()) || !Objects.equals(part.getLocatorJson(), value.locatorJson())) throw failure(409, "SOURCE_PART_FENCE_LOST");
            part.setContentText(value.content()); part.setContentHash(value.contentHash());
            part.setCharCount(value.charCount()); part.setWordCount(value.wordCount()); part.setStatus("READY");
            part.setErrorCode(null); part.setErrorDetail(null); part.setLeaseOwner(null); part.setLeaseUntil(null);
            part.setStateRevision(part.getStateRevision() + 1); audit(part, document.getUserId(), false); parts.updateById(part);
            parts.update(null, Wrappers.<AidAssistantSourceImportPart>lambdaUpdate().eq(AidAssistantSourceImportPart::getId, part.getId())
                    .set(AidAssistantSourceImportPart::getLeaseOwner, null).set(AidAssistantSourceImportPart::getLeaseUntil, null)
                    .set(AidAssistantSourceImportPart::getErrorCode, null).set(AidAssistantSourceImportPart::getErrorDetail, null));
        }
        int ready = (int) all.stream().filter(part -> "READY".equals(part.getStatus())).count();
        document.setReadyPartCount(ready); document.setErrorCode(null);
        documents.update(null, Wrappers.<AidAssistantSourceImport>lambdaUpdate().eq(AidAssistantSourceImport::getId, document.getId())
                .set(AidAssistantSourceImport::getErrorCode, null).set(AidAssistantSourceImport::getErrorDetail, null)); document.setErrorDetail(null);
        document.setStateRevision(document.getStateRevision() + 1); audit(document, document.getUserId(), false);
        if (ready == document.getPartCount()) {
            document.setStatus("FINALIZING"); documents.updateById(document);
            return true;
        }
        document.setStatus("PARTIAL"); document.setLeaseOwner(null); document.setLeaseUntil(null); documents.updateById(document);
        complete(claim.command(), "COMPLETED", null);
        return false;
    }

    private void finalizeSource(Claim claim) {
        List<AidAssistantSourceImportPart> ordered = parts.selectList(Wrappers.<AidAssistantSourceImportPart>lambdaQuery()
                .eq(AidAssistantSourceImportPart::getImportId, claim.document().getId()).eq(AidAssistantSourceImportPart::getDelFlag, "0")
                .orderByAsc(AidAssistantSourceImportPart::getPartIndex));
        StringBuilder text = new StringBuilder();
        int index = 0;
        for (AidAssistantSourceImportPart part : ordered) {
            if (part.getPartIndex() != index++ || !"READY".equals(part.getStatus()) || part.getContentText() == null
                    || !Objects.equals(SecureUtil.sha256(part.getContentText()), part.getContentHash())) throw failure(409, "SOURCE_PART_INTEGRITY_FAILED");
            if (part.getContentText().length() > MAX_CHAR_COUNT - text.length()) throw failure(422, "SOURCE_TEXT_LIMIT_EXCEEDED");
            text.append(part.getContentText());
        }
        if (ordered.size() != claim.document().getPartCount() || text.toString().isBlank()) throw failure(409, "SOURCE_PARTS_INCOMPLETE");
        if (com.aid.assistant.creative.series.FlowScreenplaySourcePolicy.applies(claim.session()))
            flowScreenplaySources.validateDocument(claim.session(), text.toString());
        List<CreativeTextChunker.Chunk> prepared = new ArrayList<>();
        CreativeTextChunker.Stats stats;
        try { stats = CreativeTextChunker.scan(new StringReader(text.toString()), 12_000, prepared::add); }
        catch (Exception failed) { throw failure(422, "SOURCE_TEXT_INVALID"); }
        transactions.executeWithoutResult(status -> {
            AidAssistantSourceImport document = lockClaim(claim);
            List<AidAssistantSourceImportPart> locked = parts.selectByImportIdForUpdate(document.getId());
            if (locked.size() != ordered.size()) throw failure(409, "SOURCE_PARTS_CHANGED");
            for (int i = 0; i < locked.size(); i++) {
                if (!"READY".equals(locked.get(i).getStatus()) || !Objects.equals(locked.get(i).getContentHash(), ordered.get(i).getContentHash())) throw failure(409, "SOURCE_PARTS_CHANGED");
            }
            AidRuntimeSourceRevision revision = new AidRuntimeSourceRevision();
            revision.setUserId(document.getUserId()); revision.setSourceKind("DOCUMENT");
            revision.setSourceObjectId(String.valueOf(document.getId()));
            String sourceRevision = "document:" + document.getId() + ":v1:" + stats.contentHash();
            revision.setSourceRevision(sourceRevision); revision.setContentHash(stats.contentHash());
            revision.setCharCount(stats.charCount()); revision.setWordCount(stats.wordCount()); revision.setChunkCount(stats.chunkCount());
            revision.setAccessScopeDigest(SecureUtil.sha256("DOCUMENT|" + document.getUserId() + "|" + document.getSessionId() + "|" + document.getProjectId()));
            revision.setStatus("ACTIVE"); revision.setDelFlag("0"); audit(revision, document.getUserId(), true); revisions.insert(revision);
            for (CreativeTextChunker.Chunk value : prepared) {
                AidRuntimeSourceChunk row = new AidRuntimeSourceChunk();
                row.setSourceRevisionId(revision.getId()); row.setChunkIndex(value.index()); row.setCharStart(value.charStart());
                row.setCharEnd(value.charEnd()); row.setCharCount(value.charEnd() - value.charStart()); row.setWordCount(value.wordCount());
                row.setContentText(value.content()); row.setContentHash(value.contentHash()); row.setStatus("ACTIVE");
                JSONObject sourceLocator = new JSONObject();
                sourceLocator.put("documentId", String.valueOf(document.getId()));
                sourceLocator.put("sessionId", String.valueOf(document.getSessionId()));
                JSONObject locator = new JSONObject(); locator.put("source", sourceLocator);
                locator.put("charStart", value.charStart()); locator.put("charEnd", value.charEnd()); row.setLocatorJson(locator.toJSONString());
                row.setCreateBy(String.valueOf(document.getUserId())); row.setCreateTime(new Date()); chunks.insert(row);
            }
            int offset = 0;
            for (AidAssistantSourceImportPart part : locked) {
                part.setCharStart(offset); offset += part.getCharCount(); part.setCharEnd(offset);
                part.setContentText(null); part.setStateRevision(part.getStateRevision() + 1);
                audit(part, document.getUserId(), false);
                parts.update(null, Wrappers.<AidAssistantSourceImportPart>lambdaUpdate().eq(AidAssistantSourceImportPart::getId, part.getId())
                        .set(AidAssistantSourceImportPart::getCharStart, part.getCharStart()).set(AidAssistantSourceImportPart::getCharEnd, part.getCharEnd())
                        .set(AidAssistantSourceImportPart::getContentText, null).set(AidAssistantSourceImportPart::getStateRevision, part.getStateRevision())
                        .set(AidAssistantSourceImportPart::getUpdateBy, part.getUpdateBy()).set(AidAssistantSourceImportPart::getUpdateTime, part.getUpdateTime()));
            }
            document.setStatus("FINALIZED"); document.setVersion(1); document.setSourceRevisionId(revision.getId());
            document.setSourceRevision(sourceRevision); document.setContentHash(stats.contentHash()); document.setCharCount(stats.charCount());
            document.setWordCount(stats.wordCount()); release(document); documents.updateById(document);
            complete(claim.command(), "COMPLETED", null);
        });
    }

    @Override
    public SourceImportViews.Command cancel(Long userId, SourceImportRequests.Cancel request) {
        Long sessionId = id(request.getSessionId()); Long documentId = id(request.getDocumentId());
        String digest = digest(CANCEL, request);
        AidRuntimeCommand result = transactions.execute(status -> {
            core.authorizeSession(userId, sessionId, true);
            AidAssistantSourceImport document = requireDocument(userId, sessionId, documentId, true);
            AidRuntimeCommand existing = findCommand(userId, sessionId, request.getClientRequestId());
            if (existing != null) return requireCommand(existing, CANCEL, digest);
            requireRevision(document, request.getExpectedStateRevision());
            if ("FINALIZED".equals(document.getStatus())) throw failure(409, "SOURCE_FINALIZED_IMMUTABLE");
            AidRuntimeCommand command = createCommand(userId, sessionId, request.getClientRequestId(), CANCEL, digest, documentId);
            document.setStatus("CANCELLED"); document.setFencingToken(document.getFencingToken() + 1);
            release(document); documents.updateById(document);
            parts.update(null, Wrappers.<AidAssistantSourceImportPart>lambdaUpdate().eq(AidAssistantSourceImportPart::getImportId, documentId)
                    .set(AidAssistantSourceImportPart::getStatus, "CANCELLED").set(AidAssistantSourceImportPart::getContentText, null)
                    .set(AidAssistantSourceImportPart::getLeaseOwner, null).set(AidAssistantSourceImportPart::getLeaseUntil, null)
                    .set(AidAssistantSourceImportPart::getUpdateBy, String.valueOf(userId)).set(AidAssistantSourceImportPart::getUpdateTime, new Date()));
            commands.update(null, Wrappers.<AidRuntimeCommand>lambdaUpdate().eq(AidRuntimeCommand::getActorId, userId)
                    .eq(AidRuntimeCommand::getSessionId, sessionId).eq(AidRuntimeCommand::getCommandType, ADVANCE)
                    .eq(AidRuntimeCommand::getStatus, "PROCESSING").apply("JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.documentId'))={0}", String.valueOf(documentId))
                    .set(AidRuntimeCommand::getStatus, "FAILED").set(AidRuntimeCommand::getErrorCode, "SOURCE_IMPORT_CANCELLED")
                    .set(AidRuntimeCommand::getUpdateBy, String.valueOf(userId)).set(AidRuntimeCommand::getUpdateTime, new Date()));
            complete(command, "COMPLETED", null); return command;
        });
        AidAssistantSourceImport document = requireDocument(userId, sessionId, documentId, false);
        blobs.deleteQuietly(document.getStorageLocator());
        return commandView(result, userId, sessionId);
    }

    private void markFailed(Claim claim, String code) {
        transactions.executeWithoutResult(status -> {
            core.authorizeSession(claim.document().getUserId(), claim.document().getSessionId(), true);
            AidAssistantSourceImport document = requireDocument(claim.document().getUserId(), claim.document().getSessionId(), claim.document().getId(), true);
            if (!Objects.equals(document.getFencingToken(), claim.document().getFencingToken()) || !Objects.equals(document.getLeaseOwner(), claim.owner())) return;
            parts.update(null, Wrappers.<AidAssistantSourceImportPart>lambdaUpdate().eq(AidAssistantSourceImportPart::getImportId, document.getId())
                    .eq(AidAssistantSourceImportPart::getLeaseOwner, claim.owner()).eq(AidAssistantSourceImportPart::getStatus, "PARSING")
                    .set(AidAssistantSourceImportPart::getStatus, "FAILED").set(AidAssistantSourceImportPart::getErrorCode, code)
                    .set(AidAssistantSourceImportPart::getLeaseOwner, null).set(AidAssistantSourceImportPart::getLeaseUntil, null)
                    .set(AidAssistantSourceImportPart::getUpdateBy, String.valueOf(document.getUserId())).set(AidAssistantSourceImportPart::getUpdateTime, new Date()));
            document.setStatus("FAILED"); document.setErrorCode(code); release(document); documents.updateById(document);
            complete(claim.command(), "FAILED", code);
        });
    }

    private AidAssistantSourceImport lockClaim(Claim claim) {
        core.authorizeSession(claim.document().getUserId(), claim.document().getSessionId(), true);
        AidAssistantSourceImport document = requireDocument(claim.document().getUserId(), claim.document().getSessionId(), claim.document().getId(), true);
        if (!Objects.equals(document.getFencingToken(), claim.document().getFencingToken())
                || !Objects.equals(document.getLeaseOwner(), claim.owner()) || document.getLeaseUntil() == null
                || !document.getLeaseUntil().after(new Date())) throw failure(409, "SOURCE_IMPORT_FENCE_LOST");
        return document;
    }

    private AidAssistantSourceImport requireDocument(Long userId, Long sessionId, Long documentId, boolean lock) {
        AidAssistantSourceImport document = lock ? documents.selectOwnedByIdForUpdate(documentId, userId, sessionId)
                : documents.selectOne(Wrappers.<AidAssistantSourceImport>lambdaQuery().eq(AidAssistantSourceImport::getId, documentId)
                .eq(AidAssistantSourceImport::getUserId, userId).eq(AidAssistantSourceImport::getSessionId, sessionId)
                .eq(AidAssistantSourceImport::getDelFlag, "0").last("LIMIT 1"));
        if (document == null) throw failure(404, "SOURCE_IMPORT_NOT_FOUND");
        return document;
    }

    private SourceImportViews.Command commandView(AidRuntimeCommand command, Long userId, Long sessionId) {
        core.authorizeSession(userId, sessionId, false);
        JSONObject value = command == null ? null : JSON.parseObject(command.getResultJson());
        if (command == null || !Objects.equals(command.getActorId(), userId) || !Objects.equals(command.getSessionId(), sessionId)
                || value == null || !"assistant-source-import-command/v1".equals(value.getString("schemaVersion"))) throw failure(500, "SOURCE_COMMAND_RECEIPT_INVALID");
        return new SourceImportViews.Command("assistant-source-import-command/v1", String.valueOf(command.getId()),
                command.getClientRequestId(), command.getStatus(), command.getErrorCode(), detailView(requireDocument(userId, sessionId, id(value.getString("documentId")), false)));
    }

    private SourceImportViews.Detail detailView(AidAssistantSourceImport document) { return detailView(document, -1, 100); }

    private SourceImportViews.Detail detailView(AidAssistantSourceImport document, int after, int limit) {
        if (after < -1 || limit < 1 || limit > 100) throw failure(422, "SOURCE_PART_PAGE_INVALID");
        List<AidAssistantSourceImportPart> metadata = parts.selectList(Wrappers.<AidAssistantSourceImportPart>lambdaQuery()
                .select(AidAssistantSourceImportPart::getPartIndex, AidAssistantSourceImportPart::getPartKind,
                        AidAssistantSourceImportPart::getLocatorJson, AidAssistantSourceImportPart::getStatus, AidAssistantSourceImportPart::getAttemptCount,
                        AidAssistantSourceImportPart::getCharStart, AidAssistantSourceImportPart::getCharEnd, AidAssistantSourceImportPart::getCharCount,
                        AidAssistantSourceImportPart::getWordCount, AidAssistantSourceImportPart::getContentHash, AidAssistantSourceImportPart::getErrorCode)
                .eq(AidAssistantSourceImportPart::getImportId, document.getId()).eq(AidAssistantSourceImportPart::getDelFlag, "0")
                .gt(AidAssistantSourceImportPart::getPartIndex, after)
                .orderByAsc(AidAssistantSourceImportPart::getPartIndex).last("LIMIT " + (limit + 1)));
        boolean more = metadata.size() > limit;
        if (more) metadata = metadata.subList(0, limit);
        int failures = Math.toIntExact(parts.selectCount(Wrappers.<AidAssistantSourceImportPart>lambdaQuery()
                .eq(AidAssistantSourceImportPart::getImportId, document.getId()).eq(AidAssistantSourceImportPart::getDelFlag, "0")
                .eq(AidAssistantSourceImportPart::getStatus, "FAILED")));
        List<SourceImportViews.Part> views = metadata.stream().map(part -> new SourceImportViews.Part(part.getPartIndex(), part.getPartKind(),
                JSON.parseObject(part.getLocatorJson()), part.getStatus(), part.getAttemptCount(), part.getCharStart(), part.getCharEnd(),
                part.getCharCount(), part.getWordCount(), part.getContentHash(), part.getErrorCode())).toList();
        return new SourceImportViews.Detail("assistant-source-import-detail/v1", String.valueOf(document.getId()),
                String.valueOf(document.getSessionId()), String.valueOf(document.getProjectId()), document.getOriginalName(), document.getMediaType(),
                document.getByteCount(), document.getByteHash(), document.getParserCode(), document.getParserVersion(), document.getStatus(),
                document.getStateRevision(), document.getPartCount(), document.getReadyPartCount(), failures, string(document.getSourceRevisionId()),
                string(document.getVersion()), document.getSourceRevision(), document.getContentHash(), document.getCharCount(), document.getWordCount(),
                views, more ? views.get(views.size() - 1).partIndex() : null, !more, document.getErrorCode(), actions(document));
    }

    private SourceImportViews.Summary summary(AidAssistantSourceImport document, int failures) {
        return new SourceImportViews.Summary(String.valueOf(document.getId()), String.valueOf(document.getSessionId()),
                String.valueOf(document.getProjectId()), document.getOriginalName(), document.getMediaType(), document.getByteCount(), document.getByteHash(),
                document.getParserCode(), document.getParserVersion(), document.getStatus(), document.getStateRevision(), document.getPartCount(),
                document.getReadyPartCount(), failures, string(document.getSourceRevisionId()), string(document.getVersion()), document.getContentHash(),
                document.getCharCount(), document.getWordCount(), document.getErrorCode(), actions(document));
    }

    private List<String> actions(AidAssistantSourceImport document) {
        if ("FINALIZED".equals(document.getStatus())) return List.of("PIN_DOCUMENT");
        if ("CANCELLED".equals(document.getStatus())) return List.of();
        if (document.getLeaseUntil() != null && document.getLeaseUntil().after(new Date())) return List.of("CANCEL");
        return List.of("FAILED".equals(document.getStatus()) ? "RETRY" : "ADVANCE", "CANCEL");
    }

    private AidRuntimeCommand createCommand(Long userId, Long sessionId, String key, String type, String digest, Long documentId) {
        AssistantDecisionCorrectionCommandNamespace.requireExternalClientRequestId(key);
        AidRuntimeCommand command = new AidRuntimeCommand(); command.setActorId(userId); command.setSessionId(sessionId);
        command.setClientRequestId(key); command.setCommandType(type); command.setPayloadDigest(digest); command.setStatus("PROCESSING"); command.setDelFlag("0");
        JSONObject receipt = new JSONObject(); receipt.put("schemaVersion", "assistant-source-import-command/v1");
        receipt.put("documentId", String.valueOf(documentId)); command.setResultJson(receipt.toJSONString());
        audit(command, userId, true); commands.insert(command); return command;
    }

    private AidRuntimeCommand findCommand(Long userId, Long sessionId, String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,64}")) throw failure(422, "SOURCE_COMMAND_KEY_INVALID");
        AssistantDecisionCorrectionCommandNamespace.requireExternalClientRequestId(key);
        return commands.selectOne(Wrappers.<AidRuntimeCommand>lambdaQuery().eq(AidRuntimeCommand::getActorId, userId)
                .eq(AidRuntimeCommand::getSessionId, sessionId).eq(AidRuntimeCommand::getClientRequestId, key)
                .eq(AidRuntimeCommand::getDelFlag, "0").last("LIMIT 1"));
    }

    private AidRuntimeCommand requireCommand(AidRuntimeCommand command, String type, String digest) {
        if (!Objects.equals(type, command.getCommandType()) || !Objects.equals(digest, command.getPayloadDigest())) throw failure(409, "SOURCE_COMMAND_REUSED");
        return command;
    }

    private void complete(AidRuntimeCommand command, String status, String code) {
        commands.update(null, Wrappers.<AidRuntimeCommand>lambdaUpdate().eq(AidRuntimeCommand::getId, command.getId())
                .eq(AidRuntimeCommand::getStatus, "PROCESSING").set(AidRuntimeCommand::getStatus, status)
                .set(AidRuntimeCommand::getErrorCode, code).set(AidRuntimeCommand::getUpdateBy, String.valueOf(command.getActorId()))
                .set(AidRuntimeCommand::getUpdateTime, new Date()));
        command.setStatus(status); command.setErrorCode(code);
    }

    private void release(AidAssistantSourceImport document) {
        document.setLeaseOwner(null); document.setLeaseUntil(null); document.setStateRevision(document.getStateRevision() + 1);
        audit(document, document.getUserId(), false);
        documents.update(null, Wrappers.<AidAssistantSourceImport>lambdaUpdate().eq(AidAssistantSourceImport::getId, document.getId())
                .set(AidAssistantSourceImport::getLeaseOwner, null).set(AidAssistantSourceImport::getLeaseUntil, null));
    }

    /** Resume only persisted, expired free parsing commands; never schedule a model call. */
    @Override
    public void recoverExpiredImports() {
        for (Long documentId : documents.selectRecoverableIds(new Date(), 10)) {
            try {
                AidAssistantSourceImport document = documents.selectById(documentId);
                if (document == null || !"0".equals(document.getDelFlag())) continue;
                AidRuntimeCommand command = commands.selectOne(Wrappers.<AidRuntimeCommand>lambdaQuery()
                        .eq(AidRuntimeCommand::getActorId, document.getUserId()).eq(AidRuntimeCommand::getSessionId, document.getSessionId())
                        .eq(AidRuntimeCommand::getCommandType, ADVANCE).eq(AidRuntimeCommand::getStatus, "PROCESSING")
                        .eq(AidRuntimeCommand::getDelFlag, "0")
                        .apply("JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.documentId'))={0}", String.valueOf(documentId))
                        .orderByDesc(AidRuntimeCommand::getId).last("LIMIT 1"));
                if (command == null) continue;
                SourceImportRequests.Advance request = JSON.parseObject(command.getResultJson())
                        .getObject("advanceRequest", SourceImportRequests.Advance.class);
                if (request == null || !Objects.equals(request.getClientRequestId(), command.getClientRequestId())
                        || !Objects.equals(request.getDocumentId(), String.valueOf(documentId))
                        || !Objects.equals(digest(ADVANCE, request), command.getPayloadDigest())) {
                    log.warn("source recovery receipt rejected, documentId={}", documentId); continue;
                }
                advance(document.getUserId(), request);
            } catch (RuntimeException failed) {
                log.info("source recovery deferred, documentId={}, type={}", documentId, failed.getClass().getSimpleName());
            }
        }
    }

    private void requireRevision(AidAssistantSourceImport document, Long revision) {
        if (!Objects.equals(document.getStateRevision(), revision)) throw failure(409, "SOURCE_REVISION_CONFLICT");
    }

    private String byteHash(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String digest(String type, Object value) { return SecureUtil.sha256(type + "\n" + JSON.toJSONString(value)); }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private Long id(String value) {
        try { long result = Long.parseLong(value); if (result < 1) throw new NumberFormatException(); return result; }
        catch (RuntimeException invalid) { throw failure(422, "SOURCE_ID_INVALID"); }
    }
    private String safeName(String name) {
        if (name == null) throw failure(422, "SOURCE_NAME_INVALID");
        String leaf = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        if (leaf.isBlank() || leaf.length() > 255 || leaf.chars().anyMatch(Character::isISOControl) || leaf.lastIndexOf('.') < 1) throw failure(422, "SOURCE_NAME_INVALID");
        return leaf;
    }
    private void audit(com.aid.common.core.domain.BaseEntity row, Long userId, boolean create) {
        if (create) { row.setCreateBy(String.valueOf(userId)); row.setCreateTime(new Date()); }
        row.setUpdateBy(String.valueOf(userId)); row.setUpdateTime(new Date());
    }
    private AssistantServiceException failure(int status, String code) {
        log.info("source import rejected, code={}", code);
        return new AssistantServiceException(status, code, "文档状态不可用", null);
    }
    private record UploadResult(AidRuntimeCommand command, boolean newBlobUsed) { }
    private record Claim(AidAssistantSourceImport document, AidRuntimeCommand command, AidAssistantSession session,
                         String owner, Set<Integer> indexes, boolean replay) { }
}
