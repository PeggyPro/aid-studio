package com.aid.canvas.service;

import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.service.BillingPreHoldCalculationService;
import com.aid.billing.service.BillingQuoteAssembler;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.canvas.controller.CanvasConversationController;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.common.exception.ServiceException;
import com.aid.generation.dto.GenerationRequests;
import com.aid.generation.service.GenerationInvocationContext;
import com.aid.generation.service.IGenericGenerationFacade;
import com.aid.generation.vo.GenerationResponse;
import com.aid.generation.vo.GenerationTaskView;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.PreparedMediaBillingInput;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.MediaBillingQuotePreparer;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Durable first-stage canvas conversation. The only paid calls use the existing media task and ledger. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CanvasConversationService {
    private static final String CHAT_PREFIX = "你是自由画布创作助手。先理解用户目标，用简洁中文提出可编辑的创作方向，不要声称已经生成画布结果。\n用户目标：";
    private static final String STEP_PREFIX = "请把以下创作目标拆成可直接制作的分镜文本。逐镜写出镜号、画面、动作、镜头和时长。只输出分镜正文。\n创作目标：";
    private final JdbcTemplate jdbc;
    private final CanvasAccessService access;
    private final CanvasMapper canvasMapper;
    private final CanvasProjectService projects;
    private final MediaBillingQuotePreparer quotePreparer;
    private final BillingPreHoldCalculationService quoteCalculator;
    private final BillingQuoteAssembler quoteAssembler;
    private final IGenericGenerationFacade generation;
    private final IMediaGenerationService media;
    private final RedissonClient redisson;

    public Map<String, Object> open(Long workspaceId, Long userId) {
        access.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO aid_canvas_agent_session(workspace_id,user_id,title) VALUES(?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, workspaceId);
            statement.setLong(2, userId);
            statement.setString(3, "新对话");
            return statement;
        }, key);
        return view(key.getKey().longValue(), userId);
    }

    public void requireScope(Long sessionId, Long workspaceId, Long userId) {
        if (!Objects.equals(number(session(sessionId, userId).get("workspace_id")), workspaceId))
            throw new ServiceException("会话与工作区不一致");
    }

    public Map<String, Object> list(Long workspaceId, Long userId) {
        access.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR", "VIEWER");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,title,updated_at FROM aid_canvas_agent_session WHERE workspace_id=? AND user_id=? ORDER BY updated_at DESC LIMIT 50",
                workspaceId, userId);
        return Map.of("items", rows.stream().map(row -> Map.of("sessionId", string(row.get("id")),
                "title", row.get("title"), "updatedAt", row.get("updated_at"))).toList());
    }

    public Map<String, Object> detail(Long sessionId, Long userId) {
        refresh(sessionId, userId);
        return view(sessionId, userId);
    }

    public Map<String, Object> quote(CanvasConversationController.Quote input, Long userId) {
        Map<String, Object> session = session(input.getSessionId(), userId);
        requireEditable(session, userId);
        String prompt = chatPrompt(input.getPrompt(), input.getReferences(), session, userId);
        BillingQuoteVO quoted = textQuote(session, userId, input.getModelCode(), prompt,
                history(input.getSessionId()));
        return quoteView(quoted);
    }

    public Map<String, Object> send(CanvasConversationController.Send input, Long userId) {
        return locked(input.getSessionId(), () -> {
            Map<String, Object> session = session(input.getSessionId(), userId);
            requireEditable(session, userId);
            String refs = JSON.toJSONString(input.getReferences() == null ? List.of() : input.getReferences());
            List<Map<String, Object>> prior = jdbc.queryForList(
                    "SELECT * FROM aid_canvas_agent_turn WHERE session_id=? AND request_key=?",
                    input.getSessionId(), input.getClientRequestId());
            if (!prior.isEmpty()) {
                Map<String, Object> existing = prior.get(0);
                if (!Objects.equals(existing.get("prompt"), input.getPrompt())
                        || !Objects.equals(existing.get("model_code"), input.getModelCode())
                        || !Objects.equals(existing.get("refs_json"), refs)
                        || money(existing.get("chat_authorized")).compareTo(nonnegative(input.getAuthorizedCredits())) != 0)
                    throw new ServiceException("请求标识已用于不同内容");
                return view(input.getSessionId(), userId);
            }
            if (active(input.getSessionId())) throw new ServiceException("请先完成当前计划");
            String prompt = chatPrompt(input.getPrompt(), input.getReferences(), session, userId);
            BillingQuoteVO quoted = textQuote(session, userId, input.getModelCode(), prompt,
                    history(input.getSessionId()));
            BigDecimal cap = nonnegative(input.getAuthorizedCredits());
            if (amount(quoted).compareTo(cap) > 0) throw new ServiceException("对话报价已变化，请重新授权");
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO aid_canvas_agent_turn(session_id,user_id,request_key,prompt,chat_input,model_code,refs_json,status,chat_quote,chat_authorized,plan_version) VALUES(?,?,?,?,?,?,?,?,?,?,1)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, input.getSessionId()); statement.setLong(2, userId);
                statement.setString(3, input.getClientRequestId()); statement.setString(4, input.getPrompt());
                statement.setString(5, prompt); statement.setString(6, input.getModelCode()); statement.setString(7, refs);
                statement.setString(8, "CHAT_SUBMITTING"); statement.setBigDecimal(9, amount(quoted));
                statement.setBigDecimal(10, cap);
                return statement;
            }, key);
            long turnId = key.getKey().longValue();
            jdbc.update("UPDATE aid_canvas_agent_session SET title=CASE WHEN title='新对话' THEN ? ELSE title END,updated_at=NOW() WHERE id=?",
                    input.getPrompt().substring(0, Math.min(60, input.getPrompt().length())), input.getSessionId());
            submitChatTask(session, latest(input.getSessionId()), userId);
            return view(input.getSessionId(), userId);
        });
    }

    public Map<String, Object> requoteChat(Long sessionId, Long userId) {
        Map<String, Object> session = session(sessionId, userId);
        requireEditable(session, userId);
        Map<String, Object> turn = latest(sessionId);
        requireStatus(turn, "PAUSED_CHAT");
        return quoteView(textQuote(session, userId, string(turn.get("model_code")),
                string(turn.get("chat_input")), history(sessionId)));
    }

    public Map<String, Object> retryChat(CanvasConversationController.Retry input, Long userId) {
        return locked(input.getSessionId(), () -> {
            Map<String, Object> session = session(input.getSessionId(), userId);
            requireEditable(session, userId);
            Map<String, Object> turn = latest(input.getSessionId());
            requireStatus(turn, "PAUSED_CHAT");
            BigDecimal quote = amount(textQuote(session, userId, string(turn.get("model_code")),
                    string(turn.get("chat_input")), history(input.getSessionId())));
            if (quote.compareTo(nonnegative(input.getAuthorizedCredits())) > 0)
                throw new ServiceException("对话报价已变化，请重新授权");
            jdbc.update("UPDATE aid_canvas_agent_turn SET chat_quote=?,chat_authorized=?,status='CHAT_SUBMITTING',error_message=NULL WHERE id=?",
                    quote, input.getAuthorizedCredits(), turn.get("id"));
            submitChatTask(session, latest(input.getSessionId()), userId);
            return view(input.getSessionId(), userId);
        });
    }

    public Map<String, Object> update(CanvasConversationController.Edit input, Long userId) {
        return locked(input.getSessionId(), () -> {
            Map<String, Object> session = session(input.getSessionId(), userId);
            requireEditable(session, userId);
            Map<String, Object> turn = latest(input.getSessionId());
            requireStatus(turn, "PLAN_READY");
            if (number(turn.get("plan_version")) != input.getPlanVersion()) throw new ServiceException("方案版本已变化");
            BillingQuoteVO quote = textQuote(session, userId, input.getModelCode(), input.getPrompt(), List.of());
            jdbc.update("UPDATE aid_canvas_agent_turn SET plan_prompt=?,plan_model=?,plan_quote=?,plan_version=plan_version+1 WHERE id=?",
                    input.getPrompt(), input.getModelCode(), amount(quote), turn.get("id"));
            return view(input.getSessionId(), userId);
        });
    }

    public Map<String, Object> quotePlan(Long sessionId, Long userId) {
        Map<String, Object> session = session(sessionId, userId);
        requireEditable(session, userId);
        Map<String, Object> turn = latest(sessionId);
        if (!List.of("PLAN_READY", "PAUSED", "AWAITING_STEP").contains(turn.get("status")))
            throw new ServiceException("当前没有待确认的计划");
        validateReferences(turn, session, userId);
        return quoteView(textQuote(session, userId, string(turn.get("plan_model")),
                string(turn.get("plan_prompt")), List.of()));
    }

    public Map<String, Object> confirm(CanvasConversationController.Confirm input, Long userId) {
        return locked(input.getSessionId(), () -> {
            Map<String, Object> session = session(input.getSessionId(), userId);
            requireEditable(session, userId);
            Map<String, Object> turn = latest(input.getSessionId());
            if (List.of("AWAITING_STEP", "ACTION_SUBMITTING", "ACTION_RUNNING", "DONE").contains(turn.get("status"))
                    && Objects.equals(turn.get("mode"), input.getMode())
                    && number(turn.get("plan_version")) == input.getPlanVersion()
                    && money(turn.get("plan_quote")).compareTo(nonnegative(input.getQuotedCredits())) == 0
                    && money(turn.get("budget_cap")).compareTo(nonnegative(input.getBudgetCap())) == 0)
                return view(input.getSessionId(), userId);
            if (!List.of("PLAN_READY", "PAUSED").contains(turn.get("status")))
                throw new ServiceException("当前计划状态已变化，请刷新");
            if (number(turn.get("plan_version")) != input.getPlanVersion()) throw new ServiceException("方案版本已变化");
            if (!List.of("MANUAL", "AUTO").contains(input.getMode())) throw new ServiceException("执行模式无效");
            BigDecimal quote = amount(textQuote(session, userId, string(turn.get("plan_model")),
                    string(turn.get("plan_prompt")), List.of()));
            if (quote.compareTo(nonnegative(input.getQuotedCredits())) != 0
                    || quote.compareTo(nonnegative(input.getBudgetCap())) > 0)
                throw new ServiceException("任务报价已变化，请重新确认");
            validateReferences(turn, session, userId);
            jdbc.update("UPDATE aid_canvas_agent_turn SET mode=?,budget_cap=?,plan_quote=?,error_message=NULL,status='AWAITING_STEP' WHERE id=?",
                    input.getMode(), input.getBudgetCap(), quote, turn.get("id"));
            if ("AUTO".equals(input.getMode())) startAction(session, latest(input.getSessionId()), userId);
            return view(input.getSessionId(), userId);
        });
    }

    public Map<String, Object> confirmStep(CanvasConversationController.Step input, Long userId) {
        return locked(input.getSessionId(), () -> {
            Map<String, Object> session = session(input.getSessionId(), userId);
            requireEditable(session, userId);
            Map<String, Object> turn = latest(input.getSessionId());
            if (List.of("ACTION_SUBMITTING", "ACTION_RUNNING", "DONE").contains(turn.get("status"))
                    && "MANUAL".equals(turn.get("mode"))
                    && number(turn.get("plan_version")) == input.getPlanVersion()
                    && money(turn.get("plan_quote")).compareTo(nonnegative(input.getQuotedCredits())) == 0)
                return view(input.getSessionId(), userId);
            requireStatus(turn, "AWAITING_STEP");
            if (number(turn.get("plan_version")) != input.getPlanVersion()) throw new ServiceException("方案版本已变化");
            if (!"MANUAL".equals(turn.get("mode"))) throw new ServiceException("当前不是手动生成模式");
            if (money(turn.get("plan_quote")).compareTo(nonnegative(input.getQuotedCredits())) != 0)
                throw new ServiceException("任务报价已变化，请重新确认");
            startAction(session, turn, userId);
            return view(input.getSessionId(), userId);
        });
    }

    public Map<String, Object> cancel(Long sessionId, Long userId) {
        return locked(sessionId, () -> {
            session(sessionId, userId);
            try { refresh(sessionId, userId); }
            catch (RuntimeException error) { log.debug("取消前任务状态检查失败 sessionId={}", sessionId, error); }
            Map<String, Object> turn = latest(sessionId);
            if (turn.isEmpty() || "DONE".equals(turn.get("status")) || "CANCELLED".equals(turn.get("status")))
                return view(sessionId, userId);
            if ("PAUSED_RESULT".equals(turn.get("status")))
                throw new ServiceException("生成已完成，结果写回待恢复，不能取消已结算任务");
            Object taskId = List.of("CHAT_RUNNING", "ACTION_RUNNING", "CHAT_SUBMITTING", "ACTION_SUBMITTING")
                    .contains(turn.get("status"))
                    ? turn.get("action_task_id") == null ? turn.get("chat_task_id") : turn.get("action_task_id")
                    : null;
            if (taskId != null) {
                MediaTaskResponse cancelled = media.cancelTextTask(number(taskId), userId);
                if (cancelled != null && "SUCCEEDED".equals(cancelled.getStatus())) {
                    refresh(sessionId, userId);
                    return view(sessionId, userId);
                }
            }
            jdbc.update("UPDATE aid_canvas_agent_turn SET status='CANCELLED' WHERE id=? AND status<>'DONE'", turn.get("id"));
            return view(sessionId, userId);
        });
    }

    @Scheduled(fixedDelay = 5000)
    public void resumeRunning() {
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT session_id,user_id FROM aid_canvas_agent_turn WHERE status IN ('CHAT_SUBMITTING','CHAT_RUNNING','ACTION_SUBMITTING','ACTION_RUNNING','PAUSED_RESULT') ORDER BY id LIMIT 50")) {
            try { refresh(number(row.get("session_id")), number(row.get("user_id"))); }
            catch (RuntimeException error) { log.warn("画布对话恢复失败 sessionId={}", row.get("session_id"), error); }
        }
    }

    private void refresh(Long sessionId, Long userId) {
        locked(sessionId, () -> {
            Map<String, Object> session = session(sessionId, userId);
            Map<String, Object> turn = latest(sessionId);
            if (turn.isEmpty()) return null;
            String status = string(turn.get("status"));
            if ("CHAT_SUBMITTING".equals(status)) {
                submitChatTask(session, turn, userId);
            } else if ("ACTION_SUBMITTING".equals(status)) {
                submitActionTask(session, turn, userId);
            } else if ("CHAT_RUNNING".equals(status) && turn.get("chat_task_id") != null) {
                GenerationTaskView task = generation.task(number(turn.get("chat_task_id")), GenerationInvocationContext.user(userId));
                if (!Objects.equals(turn.get("chat_task_status"), task.getStatus()))
                    jdbc.update("UPDATE aid_canvas_agent_turn SET chat_task_status=? WHERE id=?",
                            task.getStatus(), turn.get("id"));
                if ("SUCCEEDED".equals(task.getStatus())) {
                    String direction = string(task.getTextContent()).isBlank()
                            ? string(turn.get("prompt")) : string(task.getTextContent());
                    if (direction.length() > 8000) direction = direction.substring(0, 8000);
                    String plan = STEP_PREFIX + direction;
                    try {
                        BillingQuoteVO quote = textQuote(session, userId, string(turn.get("model_code")), plan, List.of());
                        jdbc.update("UPDATE aid_canvas_agent_turn SET reply_text=?,plan_prompt=?,plan_model=?,plan_quote=?,status='PLAN_READY' WHERE id=? AND status='CHAT_RUNNING'",
                                task.getTextContent(), plan, turn.get("model_code"), amount(quote), turn.get("id"));
                    } catch (RuntimeException unavailableModel) {
                        jdbc.update("UPDATE aid_canvas_agent_turn SET reply_text=?,plan_prompt=?,plan_model=?,plan_quote=NULL,status='PLAN_READY',error_message=? WHERE id=? AND status='CHAT_RUNNING'",
                                task.getTextContent(), plan, turn.get("model_code"), "执行模型不可报价，请在计划中选择其他模型", turn.get("id"));
                    }
                } else if (failed(task.getStatus())) fail(turn, task.getUserMessage());
            } else if (("ACTION_RUNNING".equals(status) || "PAUSED_RESULT".equals(status))
                    && turn.get("action_task_id") != null) {
                GenerationTaskView task = generation.task(number(turn.get("action_task_id")), GenerationInvocationContext.user(userId));
                if (!Objects.equals(turn.get("action_task_status"), task.getStatus()))
                    jdbc.update("UPDATE aid_canvas_agent_turn SET action_task_status=? WHERE id=?",
                            task.getStatus(), turn.get("id"));
                if ("SUCCEEDED".equals(task.getStatus())) {
                    String objectId = "agent_" + turn.get("id");
                    try {
                        projects.createAgentTextNode(number(session.get("workspace_id")), userId,
                                "agent_" + turn.get("id"), objectId, "AI 分镜", string(task.getTextContent()));
                        jdbc.update("UPDATE aid_canvas_agent_turn SET status='DONE',result_node_id=?,result_text=?,spent_credits=? WHERE id=? AND status IN ('ACTION_RUNNING','PAUSED_RESULT')",
                                objectId, task.getTextContent(), task.getActualCost() == null ? money(turn.get("plan_quote")) : task.getActualCost(), turn.get("id"));
                    } catch (RuntimeException error) {
                        jdbc.update("UPDATE aid_canvas_agent_turn SET status='PAUSED_RESULT',error_message=? WHERE id=?",
                                error.getMessage(), turn.get("id"));
                    }
                } else if (failed(task.getStatus())) fail(turn, task.getUserMessage());
            }
            return null;
        });
    }

    private void startAction(Map<String, Object> session, Map<String, Object> turn, Long userId) {
        try {
            validateReferences(turn, session, userId);
        } catch (ServiceException error) {
            jdbc.update("UPDATE aid_canvas_agent_turn SET status='PAUSED',error_message=? WHERE id=?",
                    error.getMessage(), turn.get("id"));
            throw error;
        }
        BillingQuoteVO quote = textQuote(session, userId, string(turn.get("plan_model")),
                string(turn.get("plan_prompt")), List.of());
        BigDecimal amount = amount(quote);
        if (amount.compareTo(money(turn.get("plan_quote"))) != 0
                || amount.compareTo(money(turn.get("budget_cap"))) > 0) {
            jdbc.update("UPDATE aid_canvas_agent_turn SET status='PAUSED',error_message='报价或预算已变化' WHERE id=?", turn.get("id"));
            throw new ServiceException("报价或预算已变化，请重新确认");
        }
        jdbc.update("UPDATE aid_canvas_agent_turn SET status='ACTION_SUBMITTING' WHERE id=? AND status='AWAITING_STEP'", turn.get("id"));
        submitActionTask(session, turn, userId);
    }

    private void submitChatTask(Map<String, Object> session, Map<String, Object> turn, Long userId) {
        try {
            GenerationResponse response = generation.submitText(textRequest(session, userId,
                    string(turn.get("model_code")), string(turn.get("chat_input")),
                    history(number(turn.get("session_id"))), "canvas-chat-" + turn.get("id")),
                    GenerationInvocationContext.builder().userId(userId)
                            .authorizedPreHoldCap(money(turn.get("chat_authorized"))).build());
            jdbc.update("UPDATE aid_canvas_agent_turn SET chat_task_id=?,chat_task_status=?,status='CHAT_RUNNING' WHERE id=? AND status='CHAT_SUBMITTING'",
                    Long.parseLong(String.valueOf(response.getTask().getTaskId())), response.getTask().getStatus(), turn.get("id"));
        } catch (ServiceException error) {
            if (error.getMessage() != null && error.getMessage().contains("报价已变化"))
                jdbc.update("UPDATE aid_canvas_agent_turn SET status='PAUSED_CHAT',error_message=? WHERE id=? AND status='CHAT_SUBMITTING'",
                        error.getMessage(), turn.get("id"));
            throw error;
        }
    }

    private void submitActionTask(Map<String, Object> session, Map<String, Object> turn, Long userId) {
        try {
            GenerationResponse response = generation.submitText(textRequest(session, userId,
                    string(turn.get("plan_model")), string(turn.get("plan_prompt")), List.of(),
                    "canvas-step-" + turn.get("id")), GenerationInvocationContext.builder().userId(userId)
                            .authorizedPreHoldCap(money(turn.get("budget_cap"))).build());
            jdbc.update("UPDATE aid_canvas_agent_turn SET action_task_id=?,action_task_status=?,status='ACTION_RUNNING' WHERE id=? AND status='ACTION_SUBMITTING'",
                    Long.parseLong(String.valueOf(response.getTask().getTaskId())), response.getTask().getStatus(), turn.get("id"));
        } catch (ServiceException error) {
            if (error.getMessage() != null && error.getMessage().contains("报价已变化"))
                jdbc.update("UPDATE aid_canvas_agent_turn SET status='PAUSED',error_message=? WHERE id=? AND status='ACTION_SUBMITTING'",
                        error.getMessage(), turn.get("id"));
            throw error;
        }
    }

    private BillingQuoteVO textQuote(Map<String, Object> session, Long userId, String model, String prompt,
                                     List<GenerationRequests.Message> history) {
        if (model == null || model.isBlank() || prompt == null || prompt.isBlank()) throw new ServiceException("模型与内容不能为空");
        MediaTextGenerateRequest mediaRequest = new MediaTextGenerateRequest();
        mediaRequest.setModelName(model);
        mediaRequest.setProjectId(number(session.get("project_id")));
        mediaRequest.setUserId(userId);
        mediaRequest.setPrompt(prompt);
        mediaRequest.setMessages(history.stream().map(item -> {
            MediaTextGenerateRequest.TextMessageItem message = new MediaTextGenerateRequest.TextMessageItem();
            message.setRole(item.getRole()); message.setContent(item.getContent()); return message;
        }).toList());
        PreparedMediaBillingInput prepared = quotePreparer.prepareTextBilling(mediaRequest);
        BillingCalcResult calculated = quoteCalculator.calculate(prepared.modelConfig(), prepared.billingInput());
        if (calculated == null || !calculated.isMatched())
            throw new ServiceException(calculated == null ? "计费规则缺失" : calculated.getErrorMessage());
        return quoteAssembler.single("MEDIA_TEXT", prepared.modelConfig(), calculated, 1);
    }

    private GenerationRequests.Text textRequest(Map<String, Object> session, Long userId, String model,
                                                String prompt, List<GenerationRequests.Message> history, String key) {
        GenerationRequests.Text request = new GenerationRequests.Text();
        request.setWorkspaceId(number(session.get("workspace_id")));
        request.setProjectId(number(session.get("project_id")));
        request.setClientRequestId(key);
        request.setModelName(model);
        request.setPrompt(prompt);
        request.setMessages(history);
        return request;
    }

    private List<GenerationRequests.Message> history(Long sessionId) {
        List<GenerationRequests.Message> messages = new ArrayList<>();
        for (Map<String, Object> turn : jdbc.queryForList(
                "SELECT chat_input,reply_text FROM aid_canvas_agent_turn WHERE session_id=? AND status='DONE' ORDER BY id DESC LIMIT 10", sessionId)) {
            GenerationRequests.Message user = new GenerationRequests.Message();
            user.setRole("user"); user.setContent(string(turn.get("chat_input")));
            GenerationRequests.Message assistant = new GenerationRequests.Message();
            assistant.setRole("assistant"); assistant.setContent(string(turn.get("reply_text")));
            messages.add(0, assistant); messages.add(0, user);
        }
        return messages;
    }

    private String chatPrompt(String prompt, List<CanvasConversationController.Reference> refs,
                              Map<String, Object> session, Long userId) {
        if (prompt == null || prompt.isBlank()) throw new ServiceException("消息不能为空");
        StringBuilder text = new StringBuilder(CHAT_PREFIX).append(prompt.trim());
        if (refs != null && !refs.isEmpty()) {
            if (refs.size() > 10) throw new ServiceException("引用节点过多");
            for (CanvasConversationController.Reference ref : refs) {
                CanvasObject object = reference(ref.getObjectId(), ref.getExpectedVersion(), session, userId);
                JSONObject data = JSON.parseObject(object.getContentJson());
                String content = data.getString("textContent");
                if (content == null || content.isBlank()) content = data.getString("documentContent");
                if ((content == null || content.isBlank()) && data.getJSONObject("metadata") != null) {
                    JSONObject studioData = data.getJSONObject("metadata").getJSONObject("studioData");
                    if (studioData != null) content = studioData.getString("documentContent");
                }
                if (content == null || content.isBlank()) throw new ServiceException("首期对话仅支持引用有内容的文本节点");
                text.append("\n参考节点 ").append(ref.getObjectId()).append("：")
                        .append(content, 0, Math.min(content.length(), 2000));
            }
        }
        return text.toString();
    }

    private void validateReferences(Map<String, Object> turn, Map<String, Object> session, Long userId) {
        for (JSONObject ref : JSON.parseArray(string(turn.get("refs_json")), JSONObject.class)) {
            reference(ref.getString("objectId"), ref.getLong("expectedVersion"), session, userId);
        }
    }

    private CanvasObject reference(String id, Long version, Map<String, Object> session, Long userId) {
        requireEditable(session, userId);
        CanvasObject object = canvasMapper.selectCanvasObject(number(session.get("workspace_id")), id);
        if (object == null || object.getDeleted() != 0 || !Objects.equals(object.getObjectVersion(), version))
            throw new ServiceException("来源节点版本已变化，请重新引用");
        return object;
    }

    private Map<String, Object> view(Long sessionId, Long userId) {
        Map<String, Object> session = session(sessionId, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", string(session.get("id")));
        result.put("workspaceId", string(session.get("workspace_id")));
        result.put("title", session.get("title"));
        result.put("turns", jdbc.queryForList("SELECT * FROM aid_canvas_agent_turn WHERE session_id=? ORDER BY id", sessionId)
                .stream().map(this::turnView).toList());
        return result;
    }

    private Map<String, Object> turnView(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        for (String key : List.of("id", "prompt", "model_code", "reply_text", "status", "plan_prompt",
                "plan_model", "plan_quote", "plan_version", "mode", "budget_cap", "spent_credits",
                "result_node_id", "error_message", "chat_quote", "chat_task_id", "chat_task_status",
                "action_task_id", "action_task_status")) {
            StringBuilder camelName = new StringBuilder();
            boolean upper = false;
            for (char ch : key.toCharArray()) {
                if (ch == '_') { upper = true; continue; }
                camelName.append(upper ? Character.toUpperCase(ch) : ch);
                upper = false;
            }
            String camel = camelName.toString();
        item.put(camel, key.equals("id") || key.endsWith("_id") && !key.equals("result_node_id")
                    ? row.get(key) == null ? null : string(row.get(key)) : row.get(key));
        }
        item.put("references", JSON.parseArray(string(row.get("refs_json"))));
        return item;
    }

    private Map<String, Object> session(Long sessionId, Long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.*,w.project_id FROM aid_canvas_agent_session s JOIN aid_canvas_workspace w ON w.id=s.workspace_id WHERE s.id=? AND s.user_id=?",
                sessionId, userId);
        if (rows.isEmpty()) throw new ServiceException("对话不存在或无权访问");
        access.requireWorkspace(number(rows.get(0).get("workspace_id")), userId, "OWNER", "EDITOR", "VIEWER");
        return rows.get(0);
    }

    private void requireEditable(Map<String, Object> session, Long userId) {
        access.requireWorkspace(number(session.get("workspace_id")), userId, "OWNER", "EDITOR");
    }

    private Map<String, Object> latest(Long sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM aid_canvas_agent_turn WHERE session_id=? ORDER BY id DESC LIMIT 1", sessionId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private boolean active(Long sessionId) {
        Map<String, Object> turn = latest(sessionId);
        return !turn.isEmpty() && !List.of("DONE", "FAILED", "CANCELLED").contains(turn.get("status"));
    }

    private void fail(Map<String, Object> turn, String reason) {
        jdbc.update("UPDATE aid_canvas_agent_turn SET status='FAILED',error_message=? WHERE id=?",
                reason == null ? "生成失败" : reason, turn.get("id"));
    }

    private static boolean failed(String status) { return List.of("FAILED", "CANCELLED", "CANCELED").contains(status); }
    private static void requireStatus(Map<String, Object> turn, String expected) {
        if (turn.isEmpty() || !expected.equals(turn.get("status"))) throw new ServiceException("当前计划状态已变化，请刷新");
    }
    private static BigDecimal amount(BillingQuoteVO quote) {
        if (quote == null || quote.getPreHoldAmount() == null) throw new ServiceException("模型当前无法提供权威报价");
        return quote.getPreHoldAmount();
    }
    private static BigDecimal money(Object value) { return value instanceof BigDecimal b ? b : new BigDecimal(string(value)); }
    private static BigDecimal nonnegative(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new ServiceException("积分授权无效");
        return value;
    }
    private static long number(Object value) { return Long.parseLong(string(value)); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static Map<String, Object> quoteView(BillingQuoteVO quote) {
        return Map.of("modelCode", quote.getModelCode() == null ? "" : quote.getModelCode(), "preHoldCredits", amount(quote),
                "displayText", quote.getDisplayText() == null ? "" : quote.getDisplayText());
    }
    private <T> T locked(Long sessionId, Supplier<T> action) {
        RLock lock = redisson.getLock("canvas:conversation:" + sessionId);
        try {
            if (!lock.tryLock(3, 60, TimeUnit.SECONDS)) throw new ServiceException("对话正在处理上一项操作");
            try { return action.get(); }
            finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new ServiceException("对话操作已中断");
        }
    }
}
