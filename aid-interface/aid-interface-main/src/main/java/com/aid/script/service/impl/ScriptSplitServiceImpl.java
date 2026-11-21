package com.aid.script.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidComicScriptService;
import com.aid.common.exception.ServiceException;
import com.aid.enums.ProjectTypeEnum;
import com.aid.enums.ScriptStatusEnum;
import com.aid.project.service.IProjectContentGuardService;
import com.aid.script.dto.ScriptSplitPreviewRequest;
import com.aid.script.helper.ScriptEpisodeSplitter;
import com.aid.script.helper.ScriptEpisodeSplitter.EpisodeSegment;
import com.aid.script.service.IScriptSplitService;
import com.aid.script.service.ScriptLimitsService;
import com.alibaba.fastjson2.JSON;
import cn.hutool.crypto.digest.DigestUtil;
import com.aid.script.vo.ScriptSplitConfirmVO;
import com.aid.script.vo.ScriptSplitEpisodeItemVO;
import com.aid.script.vo.ScriptSplitPreviewVO;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 剧本自动分集实现：预览只读、确认在项目锁内逐集创建剧集与剧本（版本1、使用中）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptSplitServiceImpl implements IScriptSplitService {

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 剧集状态：草稿 */
    private static final int EPISODE_STATUS_DRAFT = 0;

    /** 单次分集数量上限（防误配分集词切出海量集） */
    private static final int MAX_EPISODES = 200;

    /** 单集描述截取字数 */
    private static final int DESCRIPTION_LENGTH = 20;

    /** 单集标题最大长度（超长截断，防止分集行整段长文本进标题） */
    private static final int TITLE_MAX_LENGTH = 50;

    /**
     * HTML 标签检测（与剧本保存口径一致：仅允许 Markdown 与换行标签）。
     */
    private static final Pattern HTML_TAG_PATTERN =
            Pattern.compile("</?[a-zA-Z][a-zA-Z0-9]*(\\s[^<>]*)?/?>");

    /** 换行标签（剧本中唯一允许的 HTML 标签） */
    private static final Pattern BR_TAG_PATTERN = Pattern.compile("(?i)</?br\\s*/?>");

    /** 项目服务（归属与类型校验） */
    private final IAidComicProjectService aidComicProjectService;

    /** 剧集服务（建集与集号顺延） */
    private final IAidComicEpisodeService aidComicEpisodeService;

    /** 剧本服务（逐集建剧本记录） */
    private final IAidComicScriptService aidComicScriptService;

    /** 编程式事务模板：批量建集在锁内以事务提交，保证「事务提交后再释放锁」 */
    private final TransactionTemplate transactionTemplate;

    /** 项目内容守卫：公开期间禁止剧集增删（与直接建集接口同口径） */
    private final IProjectContentGuardService projectContentGuardService;
    private final ScriptLimitsService scriptLimitsService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public ScriptSplitPreviewVO previewSplit(ScriptSplitPreviewRequest request, Long userId) {
        validateProject(request.getProjectId(), userId, false);
        List<EpisodeSegment> segments = parseAndValidate(request);

        long firstEpisodeNo = resolveNextEpisodeNo(request.getProjectId());
        List<ScriptSplitEpisodeItemVO> items = new ArrayList<>(segments.size());
        int totalChars = 0;
        for (EpisodeSegment segment : segments) {
            ScriptSplitEpisodeItemVO item = new ScriptSplitEpisodeItemVO();
            item.setEpisodeNo(Math.toIntExact(firstEpisodeNo + segment.getSequenceNo() - 1));
            item.setTitle(resolveTitle(segment));
            item.setDescription(ScriptEpisodeSplitter.summarize(segment.getContent(), DESCRIPTION_LENGTH));
            int charCount = ScriptLimitsService.countChars(segment.getContent());
            item.setCharCount(charCount);
            totalChars += charCount;
            items.add(item);
        }
        ScriptSplitPreviewVO result = new ScriptSplitPreviewVO();
        result.setTotalEpisodes(items.size());
        result.setFirstEpisodeNo(firstEpisodeNo);
        result.setTotalCharCount(totalChars);
        result.setEpisodeKeyword(StrUtil.blankToDefault(request.getEpisodeKeyword(), "第一集").trim());
        result.setItems(items);
        return result;
    }

    @Override
    public ScriptSplitConfirmVO confirmSplit(ScriptSplitPreviewRequest request, Long userId) {
        return transactionTemplate.execute(status -> {
            AidComicProject project = validateProject(request.getProjectId(), userId, true);
            String normalizedText = request.getScriptText().replace("\\r\\n", "\\n");
            String keyword = StrUtil.blankToDefault(request.getEpisodeKeyword(), "第一集").trim();
            String digest = DigestUtil.sha256Hex(keyword + "\\n" + normalizedText);
            List<String> receipts = jdbcTemplate.queryForList(
                    "SELECT result_json FROM aid_script_import_receipt WHERE project_id = ? AND content_hash = ?",
                    String.class, request.getProjectId(), digest);
            if (!receipts.isEmpty()) {
                return JSON.parseObject(receipts.get(0), ScriptSplitConfirmVO.class);
            }
            projectContentGuardService.assertProjectEditable(request.getProjectId());
            List<EpisodeSegment> segments = parseAndValidate(request);
            long firstEpisodeNo = resolveNextEpisodeNo(request.getProjectId());
            if (request.getFirstEpisodeNo() != null && !request.getFirstEpisodeNo().equals(firstEpisodeNo)) {
                throw new ServiceException("剧集列表已变化，请重新预览再导入");
            }
            ScriptSplitConfirmVO result = doConfirmSplit(request, userId, segments, project, firstEpisodeNo);
            jdbcTemplate.update("INSERT INTO aid_script_import_receipt(project_id, content_hash, result_json) VALUES (?, ?, ?)",
                    request.getProjectId(), digest, JSON.toJSONString(result));
            return result;
        });
    }

    /**
     * 分集确认入库主体（事务内执行）：逐集创建剧集与剧本记录。
     *
     * @param request  确认入参
     * @param userId   当前用户ID
     * @param segments 已解析校验的单集列表
     * @return 入库结果
     */
    private ScriptSplitConfirmVO doConfirmSplit(ScriptSplitPreviewRequest request, Long userId,
                                                List<EpisodeSegment> segments, AidComicProject project, long nextEpisodeNo) {

        List<ScriptSplitConfirmVO.CreatedEpisodeVO> created = new ArrayList<>(segments.size());
        Date now = new Date();
        for (EpisodeSegment segment : segments) {
            String title = resolveTitle(segment);
            String description = ScriptEpisodeSplitter.summarize(segment.getContent(), DESCRIPTION_LENGTH);
            // 建集：标题/描述来自解析结果，状态草稿，步骤走 DB 默认（步骤1）
            AidComicEpisode episode = new AidComicEpisode();
            episode.setProjectId(request.getProjectId());
            episode.setUserId(userId);
            episode.setEpisodeNo(nextEpisodeNo);
            episode.setComicTitle(title);
            episode.setComicDesc(description);
            // 继承项目默认生成/创作模式（与直接建集接口同口径，保证两入口落库一致）
            episode.setGenMode(project.getDefaultGenMode());
            episode.setCreationMode(project.getDefaultCreationMode());
            episode.setStatus(EPISODE_STATUS_DRAFT);
            episode.setDelFlag(DEL_FLAG_NORMAL);
            episode.setCreateTime(now);
            episode.setCreateBy(String.valueOf(userId));
            if (!aidComicEpisodeService.save(episode)) {
                throw new ServiceException("创建剧集失败");
            }

            // 建剧本：该集正文，版本1、使用中
            AidComicScript script = new AidComicScript();
            script.setProjectId(request.getProjectId());
            script.setEpisodeId(episode.getId());
            script.setUserId(userId);
            script.setOriginalText(segment.getContent());
            script.setComicVersion(1);
            script.setStatus(ScriptStatusEnum.IN_USE.getValue());
            script.setIsExtracted(0);
            script.setDelFlag(DEL_FLAG_NORMAL);
            script.setCreateTime(now);
            script.setCreateBy(String.valueOf(userId));
            if (!aidComicScriptService.save(script)) {
                throw new ServiceException("保存剧本失败");
            }

            ScriptSplitConfirmVO.CreatedEpisodeVO item = new ScriptSplitConfirmVO.CreatedEpisodeVO();
            item.setEpisodeId(episode.getId());
            item.setEpisodeNo(nextEpisodeNo);
            item.setTitle(title);
            item.setDescription(description);
            item.setScriptId(script.getId());
            created.add(item);
            nextEpisodeNo++;
        }
        log.info("剧本分集入库完成, projectId={}, userId={}, episodes={}",
                request.getProjectId(), userId, created.size());
        ScriptSplitConfirmVO result = new ScriptSplitConfirmVO();
        result.setTotalEpisodes(created.size());
        result.setEpisodes(created);
        return result;
    }

    /**
     * 项目校验：归属当前用户且必须是剧集类型。
     *
     * @param projectId 项目ID
     * @param userId    当前用户ID
     */
    private AidComicProject validateProject(Long projectId, Long userId, boolean lock) {
        // 查询字段精简：校验需归属与类型 + 建集继承的默认模式（新增使用字段时此处必须同步补充）
        AidComicProject project = aidComicProjectService.getOne(
                Wrappers.<AidComicProject>lambdaQuery()
                        .select(AidComicProject::getId, AidComicProject::getUserId,
                                AidComicProject::getProjectType,
                                AidComicProject::getDefaultGenMode, AidComicProject::getDefaultCreationMode)
                        .eq(AidComicProject::getId, projectId)
                        .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL)
                        .last(lock, "FOR UPDATE"));
        if (Objects.isNull(project) || !Objects.equals(project.getUserId(), userId)) {
            log.error("剧本分集项目缺失或越权, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("项目不存在");
        }
        if (!Objects.equals(ProjectTypeEnum.SERIES.getValue(), project.getProjectType())) {
            log.info("剧本分集拒绝非剧集项目, projectId={}, projectType={}", projectId, project.getProjectType());
            throw new ServiceException("仅剧集可分集");
        }
        return project;
    }

    /**
     * 解析并校验分集结果（预览与确认共用同一口径，保证两次结果一致）。
     *
     * @param request 分集入参
     * @return 有序单集列表
     */
    private List<EpisodeSegment> parseAndValidate(ScriptSplitPreviewRequest request) {
        String text = request.getScriptText();
        if (text.length() > 4_000_000) {
            throw new ServiceException("剧本文本过大，请删减后重试");
        }
        assertOnlyLineBreakHtml(text);
        ScriptLimitsService.Limits limits = scriptLimitsService.current();
        ScriptLimitsService.validate(text, limits.seriesMaxChars(), "整篇剧本");
        int malformedHeading = ScriptEpisodeSplitter.firstMalformedHeadingLine(text, request.getEpisodeKeyword());
        if (malformedHeading > 0) {
            String sample = StrUtil.blankToDefault(request.getEpisodeKeyword(), "第一集").trim();
            throw new ServiceException("第" + malformedHeading + "行分集标题格式不符，请统一使用「"
                    + sample + "」的格式，并将标题单独写在一行");
        }
        List<EpisodeSegment> segments = ScriptEpisodeSplitter.split(text, request.getEpisodeKeyword());
        if (CollectionUtil.isEmpty(segments)) {
            log.info("剧本分集未命中分集词, projectId={}, keyword={}",
                    request.getProjectId(), request.getEpisodeKeyword());
            throw new ServiceException("未找到分集标题，请将第一集、第二集等标题单独写在一行");
        }
        if (segments.size() > MAX_EPISODES) {
            log.info("剧本分集集数超限, projectId={}, episodes={}, max={}",
                    request.getProjectId(), segments.size(), MAX_EPISODES);
            throw new ServiceException("分集过多");
        }
        for (EpisodeSegment segment : segments) {
            String ordinal = segment.getRawOrdinal();
            int expected = segment.getSequenceNo();
            if (ordinal != null && !matchesOrdinal(ordinal, expected)) {
                throw new ServiceException("第" + segment.getHeadingLine() + "行集号为" + ordinal
                        + "，预期第" + expected + "集，请检查重复、缺失或乱序");
            }
            if (!segment.isBodyPresent() || ScriptLimitsService.countChars(segment.getContent()) == 0) {
                throw new ServiceException("第" + segment.getHeadingLine() + "行的第" + expected + "集没有正文");
            }
            ScriptLimitsService.validate(segment.getContent(), limits.episodeMaxChars(),
                    "第" + expected + "集剧本");
        }
        return segments;
    }

    /**
     * 解析单集标题：分集行余文优先（超长截断），无余文回退「第N集」。
     *
     * @param segment 单集解析结果
     * @return 标题
     */
    private String resolveTitle(EpisodeSegment segment) {
        String title = segment.getTitle();
        if (StrUtil.isBlank(title)) {
            return "第" + segment.getSequenceNo() + "集";
        }
        String trimmed = title.trim();
        return trimmed.length() > TITLE_MAX_LENGTH ? trimmed.substring(0, TITLE_MAX_LENGTH) : trimmed;
    }

    /**
     * 取项目下一个可用集号：max(episode_no)+1（无剧集从 1 起）。
     *
     * @param projectId 项目ID
     * @return 下一个集号
     */
    private long resolveNextEpisodeNo(Long projectId) {
        // 查询字段精简：顺延集号只需最大 episode_no（新增使用字段时此处必须同步补充）
        AidComicEpisode latest = aidComicEpisodeService.getOne(
                Wrappers.<AidComicEpisode>lambdaQuery()
                        .select(AidComicEpisode::getId, AidComicEpisode::getEpisodeNo)
                        .eq(AidComicEpisode::getProjectId, projectId)
                        .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByDesc(AidComicEpisode::getEpisodeNo)
                        .last("LIMIT 1"));
        if (Objects.isNull(latest) || Objects.isNull(latest.getEpisodeNo())) {
            return 1L;
        }
        return latest.getEpisodeNo() + 1L;
    }

    /**
     * 校验剧本文本：除换行标签 {@code <br>} 外不允许任何 HTML 标签（与上传口径一致）。
     *
     * @param text 剧本文本
     */
    private void assertOnlyLineBreakHtml(String text) {
        String stripped = BR_TAG_PATTERN.matcher(text).replaceAll("");
        if (HTML_TAG_PATTERN.matcher(stripped).find()) {
            log.info("剧本分集含非法 HTML 标签, contentLen={}", text.length());
            throw new ServiceException("标签格式有误");
        }
    }

    private boolean matchesOrdinal(String rawOrdinal, int expected) {
        if (rawOrdinal.matches("[0-9]+")) {
            return rawOrdinal.replaceFirst("^0+(?!$)", "").equals(Integer.toString(expected));
        }
        String normal = cn.hutool.core.convert.NumberChineseFormatter.format(expected, false);
        if (normal.startsWith("一十")) {
            normal = normal.substring(1);
        }
        return rawOrdinal.equals(normal)
                || (normal.startsWith("十") && rawOrdinal.equals("一" + normal))
                || (expected == 2 && "两".equals(rawOrdinal))
                || (expected == 200 && "两百".equals(rawOrdinal));
    }
}
