package com.aid.assistant.service;

import com.aid.assistant.error.AssistantServiceException;
import com.aid.common.config.AidAppConfig;
import com.aid.runtime.domain.RuntimeRunLifecycle;
import com.fasterxml.jackson.core.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Archive durable conversation data before physical deletion; execution/accounting evidence stays authoritative. */
@Service @RequiredArgsConstructor @lombok.extern.slf4j.Slf4j
public class AssistantSessionDeletionService {
    private final AssistantCoreService access;
    private final JdbcTemplate jdbc;
    private static final String RUNS="select id from aid_runtime_run where session_id=?";
    private static final String INPUTS="select id from aid_runtime_input_request where run_id in ("+RUNS+")";
    private static final LinkedHashMap<String,String> TABLES=new LinkedHashMap<>();
    static {
        TABLES.put("aid_chat_session","id=?");
        TABLES.put("aid_chat_message","session_id=?");
        TABLES.put("aid_chat_message_part","message_id in (select id from aid_chat_message where session_id=?)");
        TABLES.put("aid_runtime_run","session_id=?");
        for(String table:List.of("aid_runtime_run_step","aid_runtime_plan","aid_runtime_approval","aid_runtime_input_request"))TABLES.put(table,"run_id in ("+RUNS+")");
        TABLES.put("aid_runtime_input_draft","input_request_id in ("+INPUTS+")");
        TABLES.put("aid_runtime_input_view","input_request_id in ("+INPUTS+")");
        for(String table:List.of("aid_runtime_event","aid_runtime_command","aid_runtime_replan_intent"))TABLES.put(table,"session_id=?");
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> delete(Long userId,Long sessionId,long expectedRevision,String requestKey)throws Exception {
        Path directory=Path.of(AidAppConfig.getPrivateProfile(),"assistant-sessions",userId.toString()).toAbsolutePath().normalize();
        String operation=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((sessionId+":"+expectedRevision+":"+requestKey).getBytes(StandardCharsets.UTF_8)));
        Path archive=directory.resolve(sessionId+"-"+operation+".json.gz");
        com.aid.assistant.domain.AidAssistantSession session;
        try{session=access.authorizeSession(userId,sessionId,true);}
        catch(AssistantServiceException missing){
            if("ASSISTANT_SESSION_NOT_FOUND".equals(missing.getErrorCode())&&Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS))return result(sessionId,operation,true);
            throw missing;
        }
        if(!Objects.equals(session.getStateRevision(),expectedRevision))throw failure("ASSISTANT_SESSION_REVISION_CONFLICT","会话已变化，请刷新后再删除");
        Integer running=new NamedParameterJdbcTemplate(jdbc).queryForObject(
                "select count(*) from aid_runtime_run where session_id=:sessionId and (status not in (:terminalStatuses) or lease_until>now(3))",
                Map.of("sessionId",sessionId,"terminalStatuses",RuntimeRunLifecycle.TERMINAL_STATUSES),Integer.class);
        if(running!=null&&running>0)throw failure("ASSISTANT_SESSION_DELETE_BUSY","请先结束正在执行或待核实的任务，再删除对话");
        Integer pending=jdbc.queryForObject("select count(*) from aid_media_task where status not in ('SUCCEEDED','FAILED','CANCELLED') and (biz_task_type='ASSISTANT_RUNTIME_STEP' and biz_task_id in (select id from aid_runtime_run_step where run_id in ("+RUNS+")) or id in (select l.media_task_id from aid_skill_run_task_link l join aid_runtime_run_step s on s.child_run_id=l.run_id where s.run_id in ("+RUNS+")))",Integer.class,sessionId,sessionId);
        if(pending!=null&&pending>0)throw failure("ASSISTANT_SESSION_DELETE_BUSY","仍有未核实的模型任务，暂不能删除对话");
        try {
            com.aid.common.storage.LocalStorageMigration.validateRoot(directory.toString());
            if(directory.startsWith(Path.of(AidAppConfig.getProfile()).toAbsolutePath().normalize()))throw new IOException("对话归档必须位于私有目录");
            Files.createDirectories(directory);
            if(Files.getFileStore(directory).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
            if(!Files.exists(archive,LinkOption.NOFOLLOW_LINKS))writeArchive(directory,archive,userId,sessionId,expectedRevision);
        } catch(IOException|UncheckedIOException failure) {
            log.warn("Assistant session archive failed, session={}, owner={}",sessionId,userId,failure);
            throw new AssistantServiceException(500,"ASSISTANT_SESSION_ARCHIVE_FAILED","对话归档失败，未删除对话，请稍后重试",null);
        }
        // Delete only conversation data. Finished execution, generated media and billing records remain traceable.
        for(String table:List.of("aid_chat_message_part","aid_runtime_input_draft","aid_runtime_input_view","aid_runtime_event","aid_runtime_command","aid_runtime_replan_intent","aid_chat_message"))jdbc.update("delete from "+table+" where "+TABLES.get(table),sessionId);
        jdbc.update("delete from aid_chat_session where id=? and user_id=?",sessionId,userId);
        return result(sessionId,operation,false);
    }
    private void writeArchive(Path directory,Path archive,Long userId,Long sessionId,long revision)throws Exception {
        Path temp=Files.createTempFile(directory,".session-archive-",".tmp");
        try {
            if(Files.getFileStore(temp).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));
            try(FileOutputStream file=new FileOutputStream(temp.toFile());GZIPOutputStream gzip=new GZIPOutputStream(file);JsonGenerator json=new JsonFactory().createGenerator(gzip)){
                json.writeStartObject();json.writeStringField("format","aid-assistant-session/v1");json.writeNumberField("ownerId",userId);json.writeNumberField("sessionId",sessionId);json.writeNumberField("revision",revision);json.writeObjectFieldStart("tables");
                for(var table:TABLES.entrySet()){
                    json.writeArrayFieldStart(table.getKey());
                    jdbc.query(connection -> {
                        PreparedStatement statement=connection.prepareStatement("select * from "+table.getKey()+" where "+table.getValue(),ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);
                        statement.setObject(1,sessionId);statement.setFetchSize(Integer.MIN_VALUE);return statement;
                    },(RowCallbackHandler)row->{
                        try{json.writeStartObject();ResultSetMetaData columns=row.getMetaData();for(int i=1;i<=columns.getColumnCount();i++){
                            json.writeFieldName(columns.getColumnLabel(i));Object value=row.getObject(i);
                            if(value==null)json.writeNull();else if(value instanceof Number n)json.writeNumber(n.toString());else if(value instanceof Boolean b)json.writeBoolean(b);else if(value instanceof byte[] bytes)json.writeBinary(bytes);else json.writeString(value.toString());
                        }json.writeEndObject();}catch(IOException error){throw new java.io.UncheckedIOException(error);}
                    });
                    json.writeEndArray();
                }
                json.writeEndObject();json.writeEndObject();json.flush();gzip.finish();gzip.flush();file.getFD().sync();
            }
            Files.move(temp,archive,StandardCopyOption.ATOMIC_MOVE);
            if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"))try(FileChannel channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}
        }finally{Files.deleteIfExists(temp);}
    }
    private static Map<String,Object> result(Long session,String key,boolean replay){return Map.of("sessionId",session.toString(),"deleted",true,"archived",true,"archiveKey",key,"replayed",replay,"executionRecordsPreserved",true);}
    private static AssistantServiceException failure(String code,String message){return new AssistantServiceException(409,code,message,null);}
}
