package com.aid.runtime.assistant;

import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem;
import com.aid.media.event.MediaTextTurnCapturedEvent;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import cn.hutool.crypto.SecureUtil;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

/** Private protocol archive, distinct from the optional presentation-only reasoning history. */
@Component
public final class NativeAssistantTurnArchive {
    private static final long MAX_BYTES = 16_777_216L;
    private static final Object FILE_LOCK = new Object();
    private final Path root;
    @Resource private AidRuntimeRunMapper runs;
    @Resource private AidRuntimeRunStepMapper steps;

    public NativeAssistantTurnArchive(@Value("${assistant.native-turn-archive.directory:${assistant.reasoning-archive.directory:${user.home}/.aid-private/reasoning}/native-turns}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    public synchronized boolean enrolled(AidRuntimeRun run, boolean firstTurn) {
        try {
            Path directory = directory(run, firstTurn);
            Path marker = directory.resolve("protocol.json");
            String identity = JSON.toJSONString(JSONObject.of("schemaVersion", "native-conversation/v1",
                    "runId", run.getId(), "userId", run.getUserId(), "versionDigest", run.getVersionDigest()));
            if (firstTurn) writeOnce(marker, identity);
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false;
            require(identity.equals(read(marker)), "NATIVE_HISTORY_IDENTITY_CHANGED");
            return true;
        } catch (IOException failure) { throw unavailable(); }
    }

    @EventListener
    public synchronized void capture(MediaTextTurnCapturedEvent event) {
        if (event == null || !"ASSISTANT_RUNTIME_STEP".equals(event.bizTaskType()) || event.bizTaskId() == null) return;
        var step = steps.selectById(event.bizTaskId());
        if (step == null || !"MAIN_AGENT".equals(step.getStepKind())) return;
        AidRuntimeRun run = runs.selectById(step.getRunId());
        require(run != null && Objects.equals(run.getUserId(), event.userId()), "NATIVE_HISTORY_SCOPE_INVALID");
        if (!enrolled(run, false)) return; // Never invent protocol history for existing snapshot-only runs.
        require(event.taskId() != null && (step.getMediaTaskId() == null || Objects.equals(step.getMediaTaskId(), event.taskId())), "NATIVE_HISTORY_TASK_CHANGED");
        TextMessageItem turn = JSON.parseObject(event.turnJson(), TextMessageItem.class);
        require(turn != null && "assistant".equals(turn.getRole()), "NATIVE_HISTORY_TURN_INVALID");
        JSONObject stored = JSONObject.of("schemaVersion", "native-turn/v1", "runId", run.getId(),
                "userId", run.getUserId(), "stepId", step.getId(), "taskId", event.taskId(),
                "protocol", event.protocol(), "turnJson", event.turnJson(), "sha256", SecureUtil.sha256(event.turnJson()));
        try { writeOnce(directory(run, false).resolve("turn-" + step.getId() + ".json"), stored.toJSONString()); }
        catch (IOException failure) { throw unavailable(); }
    }

    /** Optional presentation recovery reads only an existing, enrolled and identity-verified turn. */
    public synchronized TextMessageItem readIfPresent(AidRuntimeRun run, Long stepId, Long taskId) {
        if (!enrolled(run, false)) return null;
        try {
            if (!Files.exists(directory(run, false).resolve("turn-" + stepId + ".json"), LinkOption.NOFOLLOW_LINKS)) return null;
            return read(run, stepId, taskId);
        } catch (IOException failure) { throw unavailable(); }
    }

    public synchronized TextMessageItem read(AidRuntimeRun run, Long stepId, Long taskId) {
        try {
            require(stepId != null && stepId > 0 && taskId != null && taskId > 0, "NATIVE_HISTORY_IDENTITY_INVALID");
            JSONObject stored = JSON.parseObject(read(directory(run, false).resolve("turn-" + stepId + ".json")));
            require(stored != null && "native-turn/v1".equals(stored.getString("schemaVersion"))
                    && Objects.equals(run.getId(), stored.getLong("runId"))
                    && Objects.equals(run.getUserId(), stored.getLong("userId"))
                    && Objects.equals(stepId, stored.getLong("stepId"))
                    && Objects.equals(taskId, stored.getLong("taskId")), "NATIVE_HISTORY_IDENTITY_CHANGED");
            String turn = stored.getString("turnJson");
            require(turn != null && Objects.equals(SecureUtil.sha256(turn), stored.getString("sha256")), "NATIVE_HISTORY_DIGEST_CHANGED");
            TextMessageItem value = JSON.parseObject(turn, TextMessageItem.class);
            require(value != null && "assistant".equals(value.getRole()), "NATIVE_HISTORY_TURN_INVALID");
            return value;
        } catch (IOException failure) { throw unavailable(); }
    }

    private Path directory(AidRuntimeRun run, boolean create) throws IOException {
        require(run != null && run.getId() != null && run.getId() > 0 && run.getUserId() != null && run.getUserId() > 0, "NATIVE_HISTORY_SCOPE_INVALID");
        Path directory = root.resolve("user-" + run.getUserId()).resolve("run-" + run.getId());
        Path cursor = directory.getRoot();
        for (Path part : directory) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return directory;
                boolean created = false;
                try { Files.createDirectory(cursor); created = true; }
                catch (FileAlreadyExistsException race) { /* Validate the winning directory below. */ }
                require(!Files.isSymbolicLink(cursor) && Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS), "NATIVE_HISTORY_PATH_INVALID");
                if (created && Files.getFileStore(cursor).supportsFileAttributeView("posix"))
                    Files.setPosixFilePermissions(cursor, PosixFilePermissions.fromString("rwx------"));
            }
            require(!Files.isSymbolicLink(cursor) && Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS), "NATIVE_HISTORY_PATH_INVALID");
        }
        return directory;
    }

    private String read(Path file) throws IOException {
        require(!Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "NATIVE_HISTORY_UNAVAILABLE");
        require(Files.size(file) <= MAX_BYTES, "NATIVE_HISTORY_LIMIT");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private void writeOnce(Path file, String value) throws IOException {
        synchronized (FILE_LOCK) { writeOnceAcrossProcesses(file, value); }
    }

    private void writeOnceAcrossProcesses(Path file, String value) throws IOException {
        Path guard = file.resolveSibling(file.getFileName() + ".lock");
        require(!Files.isSymbolicLink(guard), "NATIVE_HISTORY_PATH_INVALID");
        try (var channel = java.nio.channels.FileChannel.open(guard,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var ignored = channel.lock()) {
            writeLocked(file, value);
        }
    }

    private void writeLocked(Path file, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= MAX_BYTES, "NATIVE_HISTORY_LIMIT");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            require(value.equals(read(file)), "NATIVE_HISTORY_RECEIPT_CONFLICT");
            return;
        }
        Path temporary = Files.createTempFile(file.getParent(), ".native-", ".tmp");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static ServiceException unavailable() { return new ServiceException("原生工具续轮记录暂不可用，尚未提交下一轮模型请求"); }
    private static void require(boolean value, String code) { if (!value) throw new ServiceException(code); }
}
