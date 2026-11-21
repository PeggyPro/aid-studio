package com.aid.discovery.service.impl;

import static com.aid.discovery.constant.DiscoveryMediaConstants.ACTION_ADMIN_CREATE;
import static com.aid.discovery.constant.DiscoveryMediaConstants.ACTION_APPROVE;
import static com.aid.discovery.constant.DiscoveryMediaConstants.ACTION_DELETE;
import static com.aid.discovery.constant.DiscoveryMediaConstants.ACTION_REJECT;
import static com.aid.discovery.constant.DiscoveryMediaConstants.ACTION_RESUBMIT;
import static com.aid.discovery.constant.DiscoveryMediaConstants.ACTION_REVOKE;
import static com.aid.discovery.constant.DiscoveryMediaConstants.ACTION_SUBMIT;
import static com.aid.discovery.constant.DiscoveryMediaConstants.ACTION_UPDATE;
import static com.aid.discovery.constant.DiscoveryMediaConstants.DEL_DELETED;
import static com.aid.discovery.constant.DiscoveryMediaConstants.DEL_NORMAL;
import static com.aid.discovery.constant.DiscoveryMediaConstants.MEDIA_IMAGE;
import static com.aid.discovery.constant.DiscoveryMediaConstants.MEDIA_VIDEO;
import static com.aid.discovery.constant.DiscoveryMediaConstants.RATIO_SOURCE_DECLARED;
import static com.aid.discovery.constant.DiscoveryMediaConstants.REVIEW_APPROVED;
import static com.aid.discovery.constant.DiscoveryMediaConstants.REVIEW_PENDING;
import static com.aid.discovery.constant.DiscoveryMediaConstants.REVIEW_REJECTED;
import static com.aid.discovery.constant.DiscoveryMediaConstants.REVIEW_REVOKED;
import static com.aid.discovery.constant.DiscoveryMediaConstants.SOURCE_ADMIN;
import static com.aid.discovery.constant.DiscoveryMediaConstants.SOURCE_USER;
import static com.aid.discovery.constant.DiscoveryMediaConstants.STATUS_DISABLED;
import static com.aid.discovery.constant.DiscoveryMediaConstants.STATUS_ENABLED;

import java.net.URI;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aid.aid.domain.AidDiscoveryMedia;
import com.aid.aid.domain.AidDiscoveryMediaReviewLog;
import com.aid.aid.service.IDiscoveryPublisherService;
import com.aid.aid.service.IAidDiscoveryMediaReviewLogService;
import com.aid.aid.service.IAidDiscoveryMediaService;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.aid.oss.properties.OssProperties.UploadTypeLimit;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.exception.ServiceException;
import com.aid.core.service.ISysUserService;
import com.aid.discovery.dto.AdminDiscoveryPageRequest;
import com.aid.discovery.dto.AdminDiscoveryReviewRequest;
import com.aid.discovery.dto.AdminDiscoverySaveRequest;
import com.aid.discovery.dto.AdminDiscoveryUpdateRequest;
import com.aid.discovery.dto.DiscoveryPageRequest;
import com.aid.discovery.dto.UserDiscoveryPageRequest;
import com.aid.discovery.dto.UserDiscoveryResubmitRequest;
import com.aid.discovery.dto.UserDiscoverySubmitRequest;
import com.aid.discovery.service.IDiscoveryMediaBusinessService;
import com.aid.discovery.util.DiscoveryAspectRatioResolver;
import com.aid.discovery.util.DiscoveryPagination;
import com.aid.discovery.vo.DiscoveryManageVO;
import com.aid.discovery.vo.DiscoveryPageResult;
import com.aid.discovery.vo.DiscoveryPublicVO;
import com.aid.discovery.vo.DiscoveryReviewLogVO;
import com.aid.media.service.VerifiedMediaMetadataService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Transactional implementation of the independent discovery review workflow. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiscoveryMediaBusinessServiceImpl implements IDiscoveryMediaBusinessService
{
    private static final String ADMIN_PUBLISHER_NAME = "AID精选";
    private static final Set<String> DEFAULT_IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "avif", "heic", "heif");
    private static final Set<String> DEFAULT_VIDEO_EXTENSIONS = Set.of(
            "mp4", "mov", "avi", "mkv", "webm", "m4v", "mpeg", "mpg");

    private final IAidDiscoveryMediaService discoveryMediaService;
    private final IAidDiscoveryMediaReviewLogService reviewLogService;
    private final IDiscoveryPublisherService discoveryPublisherService;
    private final ISysUserService sysUserService;
    private final MediaUrlResolver mediaUrlResolver;
    private final OssConfigManager ossConfigManager;
    private final VerifiedMediaMetadataService verifiedMediaMetadataService;

    /** Returns only content currently eligible for public display. */
    @Override
    public DiscoveryPageResult<DiscoveryPublicVO> pagePublic(DiscoveryPageRequest source)
    {
        DiscoveryPageRequest request = Objects.requireNonNullElseGet(source, DiscoveryPageRequest::new);
        IPage<AidDiscoveryMedia> page = discoveryMediaService.page(
                new Page<>(DiscoveryPagination.pageNum(request.getPageNum()),
                        DiscoveryPagination.pageSize(request.getPageSize(), 24)),
                baseQuery()
                        .eq(AidDiscoveryMedia::getReviewStatus, REVIEW_APPROVED)
                        .eq(AidDiscoveryMedia::getStatus, STATUS_ENABLED)
                        .like(StrUtil.isNotBlank(request.getName()), AidDiscoveryMedia::getName,
                                StrUtil.trim(request.getName()))
                        .eq(StrUtil.isNotBlank(request.getMediaType()), AidDiscoveryMedia::getMediaType,
                                request.getMediaType())
                        .orderByAsc(AidDiscoveryMedia::getSortOrder)
                        .orderByDesc(AidDiscoveryMedia::getPublishTime)
                        .orderByDesc(AidDiscoveryMedia::getId));
        Map<Long, SysUser> users = loadUsers(page.getRecords());
        List<DiscoveryPublicVO> data = page.getRecords().stream()
                .map(item -> toPublicVO(item, users.get(item.getUserId())))
                .toList();
        return new DiscoveryPageResult<>(page.getTotal(), data);
    }

    /** Returns an approved and enabled public discovery item. */
    @Override
    public DiscoveryPublicVO getPublic(Long id)
    {
        AidDiscoveryMedia media = discoveryMediaService.getOne(baseQuery()
                .eq(AidDiscoveryMedia::getId, id)
                .eq(AidDiscoveryMedia::getReviewStatus, REVIEW_APPROVED)
                .eq(AidDiscoveryMedia::getStatus, STATUS_ENABLED));
        if (media == null)
        {
            throw failure("内容不存在或不可见");
        }
        SysUser publisher = Objects.equals(media.getSourceType(), SOURCE_USER)
                ? sysUserService.selectUserById(media.getUserId()) : null;
        return toPublicVO(media, publisher);
    }

    /** Creates an image or video user submission in pending review state. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitUser(UserDiscoverySubmitRequest request, Long userId, String username)
    {
        requireUser(userId);
        NormalizedContent content = normalizeUserContent(request);
        Date now = new Date();
        AidDiscoveryMedia media = new AidDiscoveryMedia();
        applyContent(media, content);
        media.setUserId(userId);
        media.setSourceType(SOURCE_USER);
        media.setReviewStatus(REVIEW_PENDING);
        media.setSortOrder(0);
        media.setStatus(STATUS_ENABLED);
        media.setDelFlag(DEL_NORMAL);
        media.setCreateBy(operatorName(username, userId));
        media.setCreateTime(now);
        media.setUpdateBy(operatorName(username, userId));
        media.setUpdateTime(now);
        if (!discoveryMediaService.save(media))
        {
            throw failure("投稿保存失败");
        }
        saveLog(media.getId(), ACTION_SUBMIT, null, REVIEW_PENDING, null,
                SOURCE_USER, userId, operatorName(username, userId));
        return media.getId();
    }

    /** Returns submissions owned by the current user. */
    @Override
    public DiscoveryPageResult<DiscoveryManageVO> pageUser(UserDiscoveryPageRequest source, Long userId)
    {
        requireUser(userId);
        UserDiscoveryPageRequest request = Objects.requireNonNullElseGet(source, UserDiscoveryPageRequest::new);
        IPage<AidDiscoveryMedia> page = discoveryMediaService.page(
                new Page<>(DiscoveryPagination.pageNum(request.getPageNum()),
                        DiscoveryPagination.pageSize(request.getPageSize(), 20)),
                baseQuery().eq(AidDiscoveryMedia::getUserId, userId)
                        .eq(AidDiscoveryMedia::getSourceType, SOURCE_USER)
                        .orderByDesc(AidDiscoveryMedia::getCreateTime)
                        .orderByDesc(AidDiscoveryMedia::getId));
        SysUser user = sysUserService.selectUserById(userId);
        List<DiscoveryManageVO> data = page.getRecords().stream()
                .map(item -> toManageVO(item, user, false, false)).toList();
        return new DiscoveryPageResult<>(page.getTotal(), data);
    }

    /** Returns one submission owned by the current user. */
    @Override
    public DiscoveryManageVO getUser(Long id, Long userId)
    {
        requireUser(userId);
        AidDiscoveryMedia media = getOwned(id, userId);
        return toManageVO(media, sysUserService.selectUserById(userId), false, false);
    }

    /** Resubmits a rejected or revoked user item and clears previous review metadata. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resubmitUser(UserDiscoveryResubmitRequest request, Long userId, String username)
    {
        requireUser(userId);
        AidDiscoveryMedia existing = getOwned(request.getId(), userId);
        String before = existing.getReviewStatus();
        if (!Objects.equals(before, REVIEW_REJECTED) && !Objects.equals(before, REVIEW_REVOKED))
        {
            throw failure("当前投稿不能重提");
        }
        NormalizedContent content = normalizeUserContent(request);
        Date now = new Date();
        AidDiscoveryMedia update = new AidDiscoveryMedia();
        applyContent(update, content);
        update.setReviewStatus(REVIEW_PENDING);
        update.setReviewReason(null);
        update.setReviewBy(null);
        update.setReviewTime(null);
        update.setPublishTime(null);
        update.setStatus(STATUS_ENABLED);
        update.setUpdateBy(operatorName(username, userId));
        update.setUpdateTime(now);
        int changed = discoveryMediaService.updateByCondition(update, new LambdaUpdateWrapper<AidDiscoveryMedia>()
                .eq(AidDiscoveryMedia::getId, existing.getId())
                .eq(AidDiscoveryMedia::getUserId, userId)
                .eq(AidDiscoveryMedia::getReviewStatus, before)
                .eq(AidDiscoveryMedia::getDelFlag, DEL_NORMAL)
                .set(AidDiscoveryMedia::getCoverUrl, content.coverUrl())
                .set(AidDiscoveryMedia::getMediaUrl, content.mediaUrl())
                .set(AidDiscoveryMedia::getPreviewVideoUrl, content.previewVideoUrl())
                .set(AidDiscoveryMedia::getReviewReason, null)
                .set(AidDiscoveryMedia::getReviewBy, null)
                .set(AidDiscoveryMedia::getReviewTime, null)
                .set(AidDiscoveryMedia::getPublishTime, null));
        ensureChanged(changed, "投稿状态已变化");
        saveLog(existing.getId(), ACTION_RESUBMIT, before, REVIEW_PENDING, null,
                SOURCE_USER, userId, operatorName(username, userId));
    }

    /** Soft-deletes a user item and immediately removes it from public display. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long id, Long userId, String username)
    {
        requireUser(userId);
        AidDiscoveryMedia existing = getOwned(id, userId);
        Date now = new Date();
        int changed = discoveryMediaService.updateByCondition(null, new LambdaUpdateWrapper<AidDiscoveryMedia>()
                .eq(AidDiscoveryMedia::getId, id)
                .eq(AidDiscoveryMedia::getUserId, userId)
                .eq(AidDiscoveryMedia::getDelFlag, DEL_NORMAL)
                .set(AidDiscoveryMedia::getStatus, STATUS_DISABLED)
                .set(AidDiscoveryMedia::getDelFlag, DEL_DELETED)
                .set(AidDiscoveryMedia::getUpdateBy, operatorName(username, userId))
                .set(AidDiscoveryMedia::getUpdateTime, now));
        ensureChanged(changed, "投稿已删除");
        saveLog(id, ACTION_DELETE, existing.getReviewStatus(), existing.getReviewStatus(), "用户删除投稿",
                SOURCE_USER, userId, operatorName(username, userId));
    }

    /** Returns the manager discovery queue with independent review filters. */
    @Override
    public DiscoveryPageResult<DiscoveryManageVO> pageAdmin(AdminDiscoveryPageRequest source)
    {
        AdminDiscoveryPageRequest request = Objects.requireNonNullElseGet(source, AdminDiscoveryPageRequest::new);
        IPage<AidDiscoveryMedia> page = discoveryMediaService.page(
                new Page<>(DiscoveryPagination.pageNum(request.getPageNum()),
                        DiscoveryPagination.pageSize(request.getPageSize(), 20)),
                baseQuery()
                        .like(StrUtil.isNotBlank(request.getName()), AidDiscoveryMedia::getName,
                                StrUtil.trim(request.getName()))
                        .eq(StrUtil.isNotBlank(request.getMediaType()), AidDiscoveryMedia::getMediaType,
                                request.getMediaType())
                        .eq(StrUtil.isNotBlank(request.getSourceType()), AidDiscoveryMedia::getSourceType,
                                request.getSourceType())
                        .eq(StrUtil.isNotBlank(request.getReviewStatus()), AidDiscoveryMedia::getReviewStatus,
                                request.getReviewStatus())
                        .last("ORDER BY CASE review_status "
                                + "WHEN 'PENDING' THEN 1 WHEN 'APPROVED' THEN 2 "
                                + "WHEN 'REJECTED' THEN 3 WHEN 'REVOKED' THEN 4 ELSE 5 END, "
                                + "create_time DESC, id DESC"));
        Map<Long, SysUser> users = loadUsers(page.getRecords());
        List<DiscoveryManageVO> data = page.getRecords().stream()
                .map(item -> toManageVO(item, users.get(item.getUserId()), false, true)).toList();
        return new DiscoveryPageResult<>(page.getTotal(), data);
    }

    /** Returns complete manager-only detail including the reserved prompt field. */
    @Override
    public DiscoveryManageVO getAdmin(Long id)
    {
        AidDiscoveryMedia media = getExisting(id);
        SysUser user = Objects.equals(media.getSourceType(), SOURCE_USER) && media.getUserId() != null
                ? sysUserService.selectUserById(media.getUserId()) : null;
        DiscoveryManageVO detail = toManageVO(media, user, true, true);
        detail.setReviewLogs(loadReviewLogs(media.getId()));
        return detail;
    }

    /** Creates manager-owned content already approved under manager responsibility. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createAdmin(AdminDiscoverySaveRequest request, Long operatorId, String operatorName)
    {
        NormalizedContent content = normalizeAdminContent(request);
        Date now = new Date();
        AidDiscoveryMedia media = new AidDiscoveryMedia();
        applyContent(media, content);
        media.setUserId(operatorId);
        media.setSourceType(SOURCE_ADMIN);
        media.setPromptText(trimToNull(request.getPromptText()));
        media.setReviewStatus(REVIEW_APPROVED);
        media.setReviewBy(operatorName(operatorName, operatorId));
        media.setReviewTime(now);
        media.setPublishTime(now);
        media.setSortOrder(normalizeSortOrder(request.getSortOrder()));
        media.setStatus(Objects.requireNonNullElse(request.getStatus(), STATUS_ENABLED));
        media.setDelFlag(DEL_NORMAL);
        media.setCreateBy(operatorName(operatorName, operatorId));
        media.setCreateTime(now);
        media.setUpdateBy(operatorName(operatorName, operatorId));
        media.setUpdateTime(now);
        if (!discoveryMediaService.save(media))
        {
            throw failure("内容保存失败");
        }
        saveLog(media.getId(), ACTION_ADMIN_CREATE, null, REVIEW_APPROVED, "后台新增并发布",
                SOURCE_ADMIN, operatorId, operatorName(operatorName, operatorId));
        return media.getId();
    }

    /** Applies a manager edit directly and records the accountability trail. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAdmin(AdminDiscoveryUpdateRequest request, Long operatorId, String operatorName)
    {
        AidDiscoveryMedia existing = getExisting(request.getId());
        NormalizedContent content = normalizeAdminContent(request);
        Date now = new Date();
        AidDiscoveryMedia update = new AidDiscoveryMedia();
        applyContent(update, content);
        update.setPromptText(trimToNull(request.getPromptText()));
        update.setSortOrder(normalizeSortOrder(request.getSortOrder()));
        update.setStatus(Objects.requireNonNullElse(request.getStatus(), STATUS_ENABLED));
        update.setUpdateBy(operatorName(operatorName, operatorId));
        update.setUpdateTime(now);
        int changed = discoveryMediaService.updateByCondition(update, new LambdaUpdateWrapper<AidDiscoveryMedia>()
                .eq(AidDiscoveryMedia::getId, request.getId())
                .eq(AidDiscoveryMedia::getReviewStatus, existing.getReviewStatus())
                .eq(AidDiscoveryMedia::getDelFlag, DEL_NORMAL)
                .set(AidDiscoveryMedia::getMediaUrl, content.mediaUrl())
                .set(AidDiscoveryMedia::getPreviewVideoUrl, content.previewVideoUrl())
                .set(AidDiscoveryMedia::getPromptText, trimToNull(request.getPromptText())));
        ensureChanged(changed, "内容状态已变化");
        saveLog(existing.getId(), ACTION_UPDATE, existing.getReviewStatus(), existing.getReviewStatus(),
                "后台修改内容", SOURCE_ADMIN, operatorId, operatorName(operatorName, operatorId));
    }

    /** Performs approve/reject/revoke through a CAS-protected state transition. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewAdmin(AdminDiscoveryReviewRequest request, Long operatorId, String operatorName)
    {
        AidDiscoveryMedia existing = getExisting(request.getId());
        String action = request.getAction();
        String before;
        String after;
        if (ACTION_APPROVE.equals(action))
        {
            boolean restoreAdmin = Objects.equals(existing.getSourceType(), SOURCE_ADMIN)
                    && Objects.equals(existing.getReviewStatus(), REVIEW_REVOKED);
            before = restoreAdmin ? REVIEW_REVOKED : REVIEW_PENDING;
            after = REVIEW_APPROVED;
        }
        else if (ACTION_REJECT.equals(action))
        {
            before = REVIEW_PENDING;
            after = REVIEW_REJECTED;
            requireReason(request.getReason(), "拒绝时必须填写原因");
        }
        else if (ACTION_REVOKE.equals(action))
        {
            before = REVIEW_APPROVED;
            after = REVIEW_REVOKED;
            requireReason(request.getReason(), "撤销时必须填写原因");
        }
        else
        {
            throw failure("审核操作不正确");
        }
        if (!Objects.equals(existing.getReviewStatus(), before))
        {
            throw failure("当前状态不可操作");
        }
        Date now = new Date();
        String reason = trimToNull(request.getReason());
        LambdaUpdateWrapper<AidDiscoveryMedia> wrapper = new LambdaUpdateWrapper<AidDiscoveryMedia>()
                .eq(AidDiscoveryMedia::getId, request.getId())
                .eq(AidDiscoveryMedia::getReviewStatus, before)
                .eq(AidDiscoveryMedia::getDelFlag, DEL_NORMAL)
                .set(AidDiscoveryMedia::getReviewStatus, after)
                .set(AidDiscoveryMedia::getReviewReason, reason)
                .set(AidDiscoveryMedia::getReviewBy, operatorName(operatorName, operatorId))
                .set(AidDiscoveryMedia::getReviewTime, now)
                .set(AidDiscoveryMedia::getUpdateBy, operatorName(operatorName, operatorId))
                .set(AidDiscoveryMedia::getUpdateTime, now);
        if (ACTION_APPROVE.equals(action))
        {
            wrapper.set(AidDiscoveryMedia::getPublishTime, now)
                    .set(AidDiscoveryMedia::getStatus, STATUS_ENABLED);
        }
        else if (ACTION_REVOKE.equals(action))
        {
            wrapper.set(AidDiscoveryMedia::getStatus, STATUS_DISABLED);
        }
        int changed = discoveryMediaService.updateByCondition(null, wrapper);
        ensureChanged(changed, "内容状态已变化");
        saveLog(existing.getId(), action, before, after, reason,
                SOURCE_ADMIN, operatorId, operatorName(operatorName, operatorId));
    }

    /** Soft-deletes manager-visible content and records the action. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAdmin(Long id, Long operatorId, String operatorName)
    {
        AidDiscoveryMedia existing = getExisting(id);
        Date now = new Date();
        int changed = discoveryMediaService.updateByCondition(null, new LambdaUpdateWrapper<AidDiscoveryMedia>()
                .eq(AidDiscoveryMedia::getId, id)
                .eq(AidDiscoveryMedia::getDelFlag, DEL_NORMAL)
                .set(AidDiscoveryMedia::getStatus, STATUS_DISABLED)
                .set(AidDiscoveryMedia::getDelFlag, DEL_DELETED)
                .set(AidDiscoveryMedia::getUpdateBy, operatorName(operatorName, operatorId))
                .set(AidDiscoveryMedia::getUpdateTime, now));
        ensureChanged(changed, "内容已删除");
        saveLog(id, ACTION_DELETE, existing.getReviewStatus(), existing.getReviewStatus(), "后台删除内容",
                SOURCE_ADMIN, operatorId, operatorName(operatorName, operatorId));
    }

    private LambdaQueryWrapper<AidDiscoveryMedia> baseQuery()
    {
        return new LambdaQueryWrapper<AidDiscoveryMedia>()
                .eq(AidDiscoveryMedia::getDelFlag, DEL_NORMAL);
    }

    private AidDiscoveryMedia getOwned(Long id, Long userId)
    {
        AidDiscoveryMedia media = discoveryMediaService.getOne(baseQuery()
                .eq(AidDiscoveryMedia::getId, id)
                .eq(AidDiscoveryMedia::getUserId, userId)
                .eq(AidDiscoveryMedia::getSourceType, SOURCE_USER));
        if (media == null)
        {
            throw failure("投稿不存在或无权限");
        }
        return media;
    }

    private AidDiscoveryMedia getExisting(Long id)
    {
        AidDiscoveryMedia media = discoveryMediaService.getOne(baseQuery().eq(AidDiscoveryMedia::getId, id));
        if (media == null)
        {
            throw failure("发现内容不存在");
        }
        return media;
    }

    private NormalizedContent normalizeUserContent(UserDiscoverySubmitRequest request)
    {
        if (request == null)
        {
            throw failure("投稿内容不能为空");
        }
        String mediaType = StrUtil.trim(request.getMediaType());
        if (!MEDIA_VIDEO.equals(mediaType) && !MEDIA_IMAGE.equals(mediaType))
        {
            throw failure("仅支持图片或视频投稿");
        }
        return normalizeContent(mediaType, request.getName(), request.getDescription(),
                request.getCoverUrl(), request.getMediaUrl(), request.getPreviewVideoUrl(),
                request.getMediaWidth(), request.getMediaHeight(), true);
    }

    private NormalizedContent normalizeAdminContent(AdminDiscoverySaveRequest request)
    {
        if (request == null)
        {
            throw failure("发现内容不能为空");
        }
        return normalizeContent(request.getMediaType(), request.getName(), request.getDescription(),
                request.getCoverUrl(), request.getMediaUrl(), request.getPreviewVideoUrl(),
                request.getMediaWidth(), request.getMediaHeight(), false);
    }

    private NormalizedContent normalizeContent(String mediaType, String name, String description,
            String coverUrl, String mediaUrl, String previewVideoUrl, Integer width, Integer height,
            boolean allowVideoWithoutCover)
    {
        String normalizedType = StrUtil.trim(mediaType);
        if (!MEDIA_VIDEO.equals(normalizedType) && !MEDIA_IMAGE.equals(normalizedType))
        {
            throw failure("媒体类型不支持");
        }
        String normalizedName = requiredText(name, 100, "名称不能为空", "名称最多100个字符");
        String normalizedDescription = requiredText(description, 1000, "描述不能为空", "描述最多1000个字符");
        String normalizedCover = allowVideoWithoutCover && MEDIA_VIDEO.equals(normalizedType)
                && StrUtil.isBlank(coverUrl)
                ? "" : normalizeMediaUrl(coverUrl, MEDIA_IMAGE, "封面图片不能为空");
        String normalizedMedia = null;
        String normalizedPreview = null;
        if (MEDIA_VIDEO.equals(normalizedType))
        {
            normalizedMedia = normalizeMediaUrl(mediaUrl, MEDIA_VIDEO, "视频不能为空");
            if (StrUtil.isNotBlank(previewVideoUrl))
            {
                normalizedPreview = normalizeMediaUrl(previewVideoUrl, MEDIA_VIDEO, "预览视频地址不正确");
            }
        }
        else if (StrUtil.isNotBlank(mediaUrl) || StrUtil.isNotBlank(previewVideoUrl))
        {
            throw failure("图片无需媒体文件");
        }
        String metadataSource = MEDIA_VIDEO.equals(normalizedType) ? normalizedMedia : normalizedCover;
        VerifiedMediaMetadataService.Metadata metadata = verifiedMediaMetadataService.inspect(
                metadataSource, normalizedType);
        int verifiedWidth = metadata.width();
        int verifiedHeight = metadata.height();
        if (verifiedWidth <= 0 || verifiedHeight <= 0)
        {
            throw failure("媒体宽高无效");
        }
        if (!Objects.equals(width, verifiedWidth) || !Objects.equals(height, verifiedHeight))
        {
            log.info("发现内容媒体宽高已按实际文件校正: type={}, declared={}x{}, verified={}x{}",
                    normalizedType, width, height, verifiedWidth, verifiedHeight);
        }
        String ratio = DiscoveryAspectRatioResolver.resolve(verifiedWidth, verifiedHeight);
        return new NormalizedContent(normalizedType, normalizedName, normalizedDescription,
                normalizedCover, normalizedMedia, normalizedPreview, verifiedWidth, verifiedHeight, ratio);
    }

    private String normalizeMediaUrl(String url, String mediaType, String blankMessage)
    {
        if (StrUtil.isBlank(url))
        {
            throw failure(blankMessage);
        }
        String trimmed = StrUtil.trim(url);
        if (trimmed.length() > 500 || !mediaUrlResolver.isSiteMediaUrl(trimmed))
        {
            throw failure("请使用本站上传文件");
        }
        String extension = extractExtension(trimmed);
        if (!allowedExtensions(mediaType).contains(extension))
        {
            throw failure(MEDIA_VIDEO.equals(mediaType) ? "视频格式不支持" : "图片格式不支持");
        }
        return mediaUrlResolver.toRelativePath(trimmed);
    }

    private Set<String> allowedExtensions(String mediaType)
    {
        OssProperties properties = ossConfigManager.getOssProperties();
        if (properties == null)
        {
            return Collections.emptySet();
        }
        List<UploadTypeLimit> limits = properties.getUploadTypeLimits();
        if (limits != null && !limits.isEmpty())
        {
            Set<String> configured = limits.stream()
                    .filter(Objects::nonNull)
                    .filter(limit -> matchesLimitName(limit.getName(), mediaType))
                    .flatMap(limit -> Objects.requireNonNullElse(limit.getExtensions(), Collections.<String>emptySet()).stream())
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            return configured;
        }
        Set<String> globals = parseExtensions(properties.getAllowedExtensions());
        Set<String> semantic = MEDIA_VIDEO.equals(mediaType) ? DEFAULT_VIDEO_EXTENSIONS : DEFAULT_IMAGE_EXTENSIONS;
        globals.retainAll(semantic);
        return globals;
    }

    private boolean matchesLimitName(String name, String mediaType)
    {
        String normalized = StrUtil.blankToDefault(name, "").toLowerCase(Locale.ROOT);
        return MEDIA_VIDEO.equals(mediaType)
                ? normalized.contains("视频") || normalized.contains("video")
                : normalized.contains("图片") || normalized.contains("图像")
                        || normalized.contains("image") || normalized.contains("picture");
    }

    private Set<String> parseExtensions(String configured)
    {
        Set<String> values = new HashSet<>();
        if (StrUtil.isBlank(configured))
        {
            return values;
        }
        for (String item : configured.split(","))
        {
            String normalized = StrUtil.trim(item).toLowerCase(Locale.ROOT);
            while (normalized.startsWith("."))
            {
                normalized = normalized.substring(1);
            }
            if (StrUtil.isNotBlank(normalized))
            {
                values.add(normalized);
            }
        }
        return values;
    }

    private String extractExtension(String url)
    {
        try
        {
            String path = url.startsWith("http://") || url.startsWith("https://")
                    ? URI.create(url).getPath() : url.split("[?#]", 2)[0];
            int slash = path.lastIndexOf('/');
            int dot = path.lastIndexOf('.');
            return dot > slash && dot < path.length() - 1
                    ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        }
        catch (Exception e)
        {
            return "";
        }
    }

    private void applyContent(AidDiscoveryMedia media, NormalizedContent content)
    {
        media.setMediaType(content.mediaType());
        media.setName(content.name());
        media.setDescription(content.description());
        media.setCoverUrl(content.coverUrl());
        media.setMediaUrl(content.mediaUrl());
        media.setPreviewVideoUrl(content.previewVideoUrl());
        media.setMediaWidth(content.mediaWidth());
        media.setMediaHeight(content.mediaHeight());
        media.setAspectRatio(content.aspectRatio());
        media.setRatioSource(RATIO_SOURCE_DECLARED);
    }

    private Map<Long, SysUser> loadUsers(List<AidDiscoveryMedia> mediaList)
    {
        Set<Long> userIds = mediaList.stream()
                .filter(item -> Objects.equals(item.getSourceType(), SOURCE_USER))
                .map(AidDiscoveryMedia::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty())
        {
            return Collections.emptyMap();
        }
        return discoveryPublisherService.listPublicProfiles(userIds).stream()
                .collect(Collectors.toMap(SysUser::getUserId, user -> user, (left, right) -> left));
    }

    private DiscoveryPublicVO toPublicVO(AidDiscoveryMedia source, SysUser user)
    {
        DiscoveryPublicVO target = new DiscoveryPublicVO();
        target.setId(source.getId());
        target.setName(source.getName());
        target.setDescription(source.getDescription());
        target.setMediaType(source.getMediaType());
        target.setCoverUrl(source.getCoverUrl());
        target.setMediaUrl(source.getMediaUrl());
        target.setPreviewVideoUrl(StrUtil.blankToDefault(source.getPreviewVideoUrl(), source.getMediaUrl()));
        target.setMediaWidth(source.getMediaWidth());
        target.setMediaHeight(source.getMediaHeight());
        target.setAspectRatio(source.getAspectRatio());
        target.setRatioSource(source.getRatioSource());
        target.setSourceType(source.getSourceType());
        if (Objects.equals(source.getSourceType(), SOURCE_ADMIN))
        {
            target.setPublisherName(ADMIN_PUBLISHER_NAME);
        }
        else
        {
            target.setPublisherName(user == null ? "创作者" : StrUtil.blankToDefault(user.getNickName(), "创作者"));
            target.setPublisherAvatar(user == null ? null : user.getAvatar());
        }
        target.setPublishTime(source.getPublishTime());
        return target;
    }

    private DiscoveryManageVO toManageVO(AidDiscoveryMedia source, SysUser user,
            boolean includePrompt, boolean includeReviewer)
    {
        DiscoveryManageVO target = new DiscoveryManageVO();
        target.setId(source.getId());
        target.setUserId(source.getUserId());
        target.setSubmitterName(Objects.equals(source.getSourceType(), SOURCE_ADMIN)
                ? ADMIN_PUBLISHER_NAME
                : user == null ? "用户" + source.getUserId()
                        : StrUtil.blankToDefault(user.getNickName(), "用户" + source.getUserId()));
        target.setSourceType(source.getSourceType());
        target.setMediaType(source.getMediaType());
        target.setName(source.getName());
        target.setDescription(source.getDescription());
        target.setCoverUrl(source.getCoverUrl());
        target.setMediaUrl(source.getMediaUrl());
        target.setPreviewVideoUrl(source.getPreviewVideoUrl());
        target.setMediaWidth(source.getMediaWidth());
        target.setMediaHeight(source.getMediaHeight());
        target.setAspectRatio(source.getAspectRatio());
        target.setRatioSource(source.getRatioSource());
        target.setReviewStatus(source.getReviewStatus());
        target.setReviewReason(source.getReviewReason());
        if (includeReviewer)
        {
            target.setReviewBy(source.getReviewBy());
        }
        target.setReviewTime(source.getReviewTime());
        target.setPublishTime(source.getPublishTime());
        target.setSortOrder(source.getSortOrder());
        target.setStatus(source.getStatus());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        if (includePrompt)
        {
            target.setPromptText(source.getPromptText());
        }
        return target;
    }

    private List<DiscoveryReviewLogVO> loadReviewLogs(Long mediaId)
    {
        return reviewLogService.list(new LambdaQueryWrapper<AidDiscoveryMediaReviewLog>()
                .select(AidDiscoveryMediaReviewLog::getAction,
                        AidDiscoveryMediaReviewLog::getBeforeStatus,
                        AidDiscoveryMediaReviewLog::getAfterStatus,
                        AidDiscoveryMediaReviewLog::getReason,
                        AidDiscoveryMediaReviewLog::getOperatorType,
                        AidDiscoveryMediaReviewLog::getOperatorName,
                        AidDiscoveryMediaReviewLog::getCreateTime)
                .eq(AidDiscoveryMediaReviewLog::getMediaId, mediaId)
                .eq(AidDiscoveryMediaReviewLog::getDelFlag, DEL_NORMAL)
                .orderByDesc(AidDiscoveryMediaReviewLog::getCreateTime)
                .orderByDesc(AidDiscoveryMediaReviewLog::getId)).stream()
                .map(this::toReviewLogVO)
                .toList();
    }

    private DiscoveryReviewLogVO toReviewLogVO(AidDiscoveryMediaReviewLog source)
    {
        DiscoveryReviewLogVO target = new DiscoveryReviewLogVO();
        target.setAction(source.getAction());
        target.setBeforeStatus(source.getBeforeStatus());
        target.setAfterStatus(source.getAfterStatus());
        target.setReason(source.getReason());
        target.setOperatorType(source.getOperatorType());
        target.setOperatorName(source.getOperatorName());
        target.setCreateTime(source.getCreateTime());
        return target;
    }

    private void saveLog(Long mediaId, String action, String before, String after, String reason,
            String operatorType, Long operatorId, String operatorName)
    {
        AidDiscoveryMediaReviewLog log = new AidDiscoveryMediaReviewLog();
        log.setMediaId(mediaId);
        log.setAction(action);
        log.setBeforeStatus(before);
        log.setAfterStatus(after);
        log.setReason(reason);
        log.setOperatorType(operatorType);
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setDelFlag(DEL_NORMAL);
        log.setCreateBy(operatorName);
        log.setCreateTime(new Date());
        if (!reviewLogService.save(log))
        {
            throw failure("审核记录保存失败");
        }
    }

    private String requiredText(String value, int maxLength, String blankMessage, String lengthMessage)
    {
        String normalized = StrUtil.trim(value);
        if (StrUtil.isBlank(normalized))
        {
            throw failure(blankMessage);
        }
        if (normalized.length() > maxLength)
        {
            throw failure(lengthMessage);
        }
        return normalized;
    }

    private String trimToNull(String value)
    {
        return StrUtil.isBlank(value) ? null : StrUtil.trim(value);
    }

    private void requireReason(String reason, String message)
    {
        if (StrUtil.isBlank(reason))
        {
            throw failure(message);
        }
    }

    private void requireUser(Long userId)
    {
        if (userId == null || userId <= 0)
        {
            throw failure("登录信息无效");
        }
    }

    private int normalizeSortOrder(Integer value)
    {
        int normalized = Objects.requireNonNullElse(value, 0);
        if (normalized < 0 || normalized > 999999)
        {
            throw failure("排序范围不正确");
        }
        return normalized;
    }

    private String operatorName(String name, Long id)
    {
        return StrUtil.blankToDefault(StrUtil.trim(name), String.valueOf(id));
    }

    private void ensureChanged(int changed, String message)
    {
        if (changed != 1)
        {
            throw failure(message);
        }
    }

    private ServiceException failure(String message)
    {
        log.warn("发现内容业务校验未通过: {}", message);
        return new ServiceException(message);
    }

    private record NormalizedContent(String mediaType, String name, String description,
            String coverUrl, String mediaUrl, String previewVideoUrl,
            Integer mediaWidth, Integer mediaHeight, String aspectRatio)
    {
    }
}
