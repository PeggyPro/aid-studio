package com.aid.discovery.seo;

import com.aid.aid.domain.AidDiscoveryMedia;
import com.aid.aid.service.IAidDiscoveryMediaService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.discovery.service.IDiscoveryMediaBusinessService;
import com.aid.discovery.vo.DiscoveryPublicVO;
import com.aid.seo.model.SeoModels.ContentCandidate;
import com.aid.seo.model.SeoModels.PublicDocument;
import com.aid.seo.service.PublicPageResolver;
import com.aid.seo.service.SeoPageContributor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 将当前已公开的发现媒体接入站点扫描及实时页面文档。 */
@Component
@RequiredArgsConstructor
public class DiscoverySeoContributor implements SeoPageContributor, PublicPageResolver {
    private final IAidDiscoveryMediaService mediaService;
    private final IDiscoveryMediaBusinessService discoveryService;
    private final MediaUrlResolver mediaUrlResolver;

    @Override
    public String sourceType() { return "DISCOVERY"; }

    @Override
    public List<ContentCandidate> listIndexablePages() {
        // Anonymous discovery listings include administrator-published content only.
        return mediaService.list(new LambdaQueryWrapper<AidDiscoveryMedia>()
                .select(AidDiscoveryMedia::getId, AidDiscoveryMedia::getName, AidDiscoveryMedia::getDescription,
                        AidDiscoveryMedia::getCoverUrl, AidDiscoveryMedia::getMediaType,
                        AidDiscoveryMedia::getPublishTime, AidDiscoveryMedia::getUpdateTime)
                .eq(AidDiscoveryMedia::getDelFlag, "0").eq(AidDiscoveryMedia::getStatus, "0")
                .eq(AidDiscoveryMedia::getReviewStatus, "APPROVED").eq(AidDiscoveryMedia::getSourceType, "ADMIN"))
                .stream().map(media -> {
                    ContentCandidate page = new ContentCandidate();
                    page.setSourceId(media.getId().toString());
                    page.setPagePath("/discovery/" + media.getId());
                    page.setPageTitle(media.getName());
                    page.setMetaDescription(media.getDescription());
                    page.setMetaKeywords(Objects.toString(media.getName(), "") + ",AI创作");
                    page.setOgImageUrl(media.getCoverUrl());
                    page.setIndexable(true);
                    page.setSitemapEnabled(true);
                    page.setSourceUpdateTime(Objects.isNull(media.getUpdateTime()) ? media.getPublishTime() : media.getUpdateTime());
                    return page;
                }).toList();
    }

    @Override
    public PublicDocument resolvePublicPage(String path) {
        if (!path.matches("/discovery/[1-9][0-9]{0,17}")) return null;
        DiscoveryPublicVO detail;
        try {
            detail = discoveryService.getPublic(Long.valueOf(path.substring("/discovery/".length())));
        } catch (ServiceException ex) { return null; }
        PublicDocument page = new PublicDocument();
        page.setTitle(detail.getName());
        page.setDescription(detail.getDescription());
        page.setKeywords(Objects.toString(detail.getName(), "") + ",AI创作");
        page.setImageUrl(mediaUrlResolver.toFullUrl(detail.getCoverUrl()));
        if (Objects.equals(detail.getMediaType(), "video")) page.setVideoUrl(mediaUrlResolver.toFullUrl(detail.getMediaUrl()));
        page.setInteractivePath("/discovery/?id=" + detail.getId());
        return page;
    }
}
