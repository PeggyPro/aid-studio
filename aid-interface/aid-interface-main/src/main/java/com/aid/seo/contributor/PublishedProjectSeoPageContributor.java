package com.aid.seo.contributor;

import com.aid.aid.domain.AidComicProject;
import com.aid.aid.mapper.AidComicProjectMapper;
import com.aid.seo.model.SeoModels.ContentCandidate;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.project.service.IPublicProjectBusinessService;
import com.aid.project.vo.PublicProjectDetailVO;
import com.aid.seo.model.SeoModels.PublicDocument;
import com.aid.seo.service.PublicPageResolver;
import com.aid.seo.service.SeoPageContributor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/** 把当前仍可公开访问的作品贡献给通用 SEO 扫描器。 */
@Component
@RequiredArgsConstructor
public class PublishedProjectSeoPageContributor implements SeoPageContributor, PublicPageResolver {
    private static final String SOURCE_TYPE = "PUBLISHED_PROJECT";
    private final AidComicProjectMapper projectMapper;
    private final IPublicProjectBusinessService publicProjectService;
    private final MediaUrlResolver mediaUrlResolver;

    @Override
    public PublicDocument resolvePublicPage(String path) {
        if (!path.matches("/case/[1-9][0-9]{0,17}")) return null;
        PublicProjectDetailVO detail;
        try {
            detail = publicProjectService.getPublicProjectDetail(Long.valueOf(path.substring(6)));
        } catch (ServiceException ex) { return null; }
        PublicDocument page = new PublicDocument();
        page.setTitle(detail.getProjectName());
        page.setDescription(detail.getProjectDesc());
        page.setKeywords(String.join(",", nonBlank(detail.getProjectName()), nonBlank(detail.getVideoStyleType()), typeName(detail.getProjectType()), "AI视频作品"));
        page.setImageUrl(mediaUrlResolver.toFullUrl(detail.getCoverUrl()));
        page.setVideoUrl(mediaUrlResolver.toFullUrl(detail.getFinalVideoUrl()));
        page.setInteractivePath("/case/?id=" + detail.getId());
        return page;
    }

    @Override
    public String sourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public List<ContentCandidate> listIndexablePages() {
        List<AidComicProject> projects = projectMapper.selectList(new LambdaQueryWrapper<AidComicProject>()
                .select(AidComicProject::getId, AidComicProject::getProjectName,
                        AidComicProject::getProjectDesc, AidComicProject::getProjectType,
                        AidComicProject::getVideoStyleType, AidComicProject::getCoverUrl,
                        AidComicProject::getPublishTime, AidComicProject::getUpdateTime)
                .eq(AidComicProject::getDelFlag, "0")
                .eq(AidComicProject::getIsPublic, "1")
                .in(AidComicProject::getStatus, 3, 4)
                .orderByDesc(AidComicProject::getPublishTime)
                .orderByDesc(AidComicProject::getId));
        return projects.stream().map(this::toCandidate).toList();
    }

    private ContentCandidate toCandidate(AidComicProject project) {
        ContentCandidate candidate = new ContentCandidate();
        candidate.setSourceId(String.valueOf(project.getId()));
        candidate.setPagePath("/case/" + project.getId());
        candidate.setPageTitle(project.getProjectName());
        candidate.setMetaDescription(project.getProjectDesc());
        candidate.setMetaKeywords(String.join(",", nonBlank(project.getProjectName()),
                nonBlank(project.getVideoStyleType()), typeName(project.getProjectType()), "AI视频作品"));
        candidate.setOgImageUrl(project.getCoverUrl());
        candidate.setIndexable(true);
        candidate.setSitemapEnabled(true);
        candidate.setSourceUpdateTime(latest(project.getPublishTime(), project.getUpdateTime()));
        return candidate;
    }

    private Date latest(Date left, Date right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.after(right) ? left : right;
    }

    private String typeName(String type) {
        if ("series".equalsIgnoreCase(type)) return "AI剧集";
        if ("movie".equalsIgnoreCase(type)) return "AI电影";
        return "AI视频";
    }

    private String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }
}
