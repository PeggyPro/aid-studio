package com.aid.skill.service;

import com.aid.common.exception.ServiceException;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillRelation;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillRelationMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 校验当前 Runtime 用户入口及其固定子版本是否完整可执行。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillRuntimeEntrypointReadinessService {
    private static final String NORMAL = "0";
    private static final String ENTRYPOINT = SkillRuntimeCapabilities.SCOPE_ENTRYPOINT;
    private static final String INTERNAL = "INTERNAL";
    private static final String PUBLIC = SkillRuntimeCapabilities.VISIBILITY_PUBLIC;
    private static final String PRIVATE = "PRIVATE";
    private static final String PLATFORM = SkillRuntimeCapabilities.OWNER_PLATFORM;
    private static final String USER = "USER";
    private static final String CHILD = "CHILD";
    private static final String ORCHESTRATOR = "ORCHESTRATOR";
    private static final String PROMPT = "PROMPT";
    private static final String PUBLISHED = "PUBLISHED";
    private static final Map<String, Set<String>> REQUIRED_CHILDREN = Map.of(
            SkillRuntimeCapabilities.SCREENPLAY, Set.of("screenplay_write", "screenplay_review"));

    private final AidSkillMapper skillMapper;
    private final AidSkillRelationMapper relationMapper;
    private final SkillModelService skillModelService;
    private final SkillPackageResourceLoader packageResourceLoader;

    /** 返回批量校验通过的入口；任一固定包损坏时按安全原则不暴露入口。 */
    public Map<Long, ReadyEntrypoint> findReady(List<AidSkill> identities) {
        try {
            return resolve(identities);
        } catch (RuntimeException error) {
            log.error("Skill Runtime入口就绪校验失败, candidateCount={}, errorType={}",
                    identities == null ? 0 : identities.size(), error.getClass().getSimpleName(), error);
            return Map.of();
        }
    }

    /** 校验单个入口并返回固定的根版本和子版本快照。 */
    public ReadyEntrypoint requireReady(AidSkill identity) {
        Map<Long, ReadyEntrypoint> ready = resolve(List.of(identity));
        ReadyEntrypoint result = ready.get(identity.getId());
        if (result == null) {
            log.warn("Skill Runtime入口未就绪, skillId={}, skillCode={}", identity.getId(), identity.getSkillCode());
            throw new ServiceException("Skill入口不可用");
        }
        return result;
    }

    /** Resolve one immutable root version even if the identity's active pointer changes concurrently. */
    public ReadyEntrypoint requireReady(AidSkill identity, Long expectedVersionId, String expectedPackageDigest) {
        if (identity == null || expectedVersionId == null || expectedVersionId <= 0
                || expectedPackageDigest == null || !expectedPackageDigest.matches("[0-9a-f]{64}")) {
            throw new ServiceException("Skill fixed version identity is invalid");
        }
        AidSkill pinned = new AidSkill();
        pinned.setId(identity.getId());
        pinned.setSkillCode(identity.getSkillCode());
        pinned.setOwnerType(identity.getOwnerType());
        pinned.setVisibility(identity.getVisibility());
        pinned.setInvocationScope(identity.getInvocationScope());
        pinned.setStatus(identity.getStatus());
        pinned.setDelFlag(identity.getDelFlag());
        pinned.setCurrentVersionId(expectedVersionId);
        ReadyEntrypoint result = resolve(List.of(pinned)).get(identity.getId());
        if (result == null || !expectedVersionId.equals(result.rootVersion().getId())
                || !expectedPackageDigest.equals(result.rootVersion().getPackageDigest())) {
            throw new ServiceException("Skill fixed version is unavailable");
        }
        return result;
    }

    /**
     * Shared immutable package gate for platform built-ins. Identity activation/current-pointer
     * checks remain with the caller so disabled and published-inactive states can be reported
     * precisely. Child relations are allowed; this is a package gate, not a single-method gate.
     */
    public boolean isPublicPlatformPackageVersionReady(AidSkill identity, AidSkillVersion version) {
        if (identity == null || version == null
                || !PLATFORM.equals(identity.getOwnerType())
                || !PUBLIC.equals(identity.getVisibility())
                || !NORMAL.equals(identity.getDelFlag())
                || !Objects.equals(version.getSkillId(), identity.getId())
                || !PUBLIC.equals(version.getVisibility())
                || !PUBLISHED.equals(version.getPublishStatus())
                || !PROMPT.equals(version.getExecutorType())
                || !NORMAL.equals(version.getStatus()) || !NORMAL.equals(version.getDelFlag())
                || !validDigest(version.getPackageDigest())
                || !skillModelService.hasActiveCandidate(version)) {
            return false;
        }
        try {
            packageResourceLoader.verifyVersion(identity.getSkillCode(), version);
            return true;
        } catch (RuntimeException error) {
            log.warn("平台Skill包未就绪, skillId={}, versionId={}, errorType={}",
                    identity.getId(), version.getId(), error.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Resolve one immutable, single-PROMPT method for the Assistant runtime. This deliberately excludes the
     * legacy ORCHESTRATOR entrypoint and every version with child relations, so it cannot create a questionnaire
     * or silently switch to another active version.
     */
    public ReadyEntrypoint requireReadyMethod(AidSkill identity, Long expectedVersionId,
                                              String expectedPackageDigest) {
        return requireReadyMethod(identity, expectedVersionId, expectedPackageDigest, null, false);
    }

    /** Resolve the authorized digest from storage before validating a requester-scoped method. */
    public ReadyEntrypoint requireReadyMethod(AidSkill identity, Long expectedVersionId,
                                              Long requesterUserId, boolean admittedRun) {
        if (identity == null || expectedVersionId == null) {
            throw new ServiceException("Assistant Skill fixed method identity is invalid");
        }
        SkillPackageResourceLoader.AuthorizedPackage authorized = loadAuthorizedMethodVersion(
                identity, expectedVersionId, requesterUserId, admittedRun);
        return validateReadyMethod(identity, expectedVersionId,
                authorized.version().getPackageDigest(), requesterUserId, admittedRun, authorized);
    }

    /**
     * Resolve an authorized immutable prompt package for Assistant planning/execution. Platform INTERNAL
     * methods remain fixed Agent dependencies; USER ENTRYPOINT packages are resolved per requester and are
     * never copied into a global Agent binding. admittedRun is reserved for a persisted approved run whose
     * user/version/digest have already been frozen by the runtime.
     */
    public ReadyEntrypoint requireReadyMethod(AidSkill identity, Long expectedVersionId,
                                              String expectedPackageDigest, Long requesterUserId,
                                              boolean admittedRun) {
        if (identity == null || expectedVersionId == null || expectedVersionId <= 0
                || expectedPackageDigest == null || !expectedPackageDigest.matches("[0-9a-f]{64}")) {
            throw new ServiceException("Assistant Skill fixed method identity is invalid");
        }
        SkillPackageResourceLoader.AuthorizedPackage authorized = loadAuthorizedMethodVersion(
                identity, expectedVersionId, requesterUserId, admittedRun);
        return validateReadyMethod(identity, expectedVersionId, expectedPackageDigest, requesterUserId, admittedRun, authorized);
    }

    /** Reuse the authorization result already loaded in this invocation. */
    private ReadyEntrypoint validateReadyMethod(AidSkill identity, Long expectedVersionId,
                                               String expectedPackageDigest, Long requesterUserId, boolean admittedRun,
                                               SkillPackageResourceLoader.AuthorizedPackage authorized) {
        if (expectedVersionId == null || expectedVersionId <= 0
                || expectedPackageDigest == null || !expectedPackageDigest.matches("[0-9a-f]{64}")) {
            throw new ServiceException("Assistant Skill fixed method identity is invalid");
        }
        AidSkill authorizedIdentity = authorized.skill();
        AidSkillVersion version = authorized.version();
        Long relationCount = version == null ? 0L : relationMapper.selectCount(
                Wrappers.<AidSkillRelation>lambdaQuery()
                        .eq(AidSkillRelation::getParentVersionId, expectedVersionId)
                        .eq(AidSkillRelation::getDelFlag, NORMAL));
        boolean fixedPlatformMethod = !admittedRun
                && Objects.equals(authorizedIdentity.getOwnerType(), PLATFORM)
                && Objects.equals(authorizedIdentity.getVisibility(), PUBLIC)
                && Objects.equals(version.getVisibility(), PUBLIC)
                && Objects.equals(version.getPublishStatus(), PUBLISHED)
                && Objects.equals(version.getInvocationScope(), INTERNAL);
        boolean platformEntrypoint = !admittedRun
                && Objects.equals(authorizedIdentity.getOwnerType(), PLATFORM)
                && Objects.equals(authorizedIdentity.getVisibility(), PUBLIC)
                && Objects.equals(version.getVisibility(), PUBLIC)
                && Objects.equals(version.getPublishStatus(), PUBLISHED)
                && Objects.equals(version.getInvocationScope(), ENTRYPOINT);
        boolean authorizedUserEntrypoint = !admittedRun
                && Objects.equals(authorizedIdentity.getOwnerType(), USER)
                && Set.of(PUBLIC, PRIVATE).contains(authorizedIdentity.getVisibility())
                && Objects.equals(authorizedIdentity.getInvocationScope(), ENTRYPOINT)
                && Set.of(PUBLIC, PRIVATE).contains(version.getVisibility())
                && Objects.equals(version.getInvocationScope(), ENTRYPOINT)
                && (PUBLIC.equals(authorizedIdentity.getVisibility())
                    || Objects.equals(authorizedIdentity.getOwnerUserId(), requesterUserId));
        // This branch is reachable only after the runtime proves a persisted owned run,
        // frozen Agent version/digest and APPROVED plan revision with the exact Skill
        // version/package digest. The flag is internal and absent from HTTP/tool schemas.
        // Live visibility, scope, status, deletion and current pointers may change after
        // admission, so historical recovery deliberately does not reapply those gates.
        boolean admittedFixedMethod = admittedRun
                && Set.of(PLATFORM, USER).contains(authorizedIdentity.getOwnerType());
        boolean ready = Objects.equals(identity.getId(), authorizedIdentity.getId())
                && Objects.equals(identity.getSkillCode(), authorizedIdentity.getSkillCode())
                && (fixedPlatformMethod || platformEntrypoint || authorizedUserEntrypoint
                    || admittedFixedMethod)
                && (admittedRun || (Objects.equals(authorizedIdentity.getStatus(), NORMAL)
                    && Objects.equals(authorizedIdentity.getDelFlag(), NORMAL)))
                && version != null && Objects.equals(version.getSkillId(), identity.getId())
                && Objects.equals(version.getExecutorType(), PROMPT)
                && (!admittedRun || "TEXT".equalsIgnoreCase(SkillModelService.outputKind(version)))
                // USER packages retain private publication semantics; platform packages are
                // constrained by the platform predicates above to PUBLIC/PUBLISHED.
                && (PRIVATE.equals(version.getPublishStatus()) || PUBLISHED.equals(version.getPublishStatus()))
                && Objects.equals(version.getPackageDigest(), expectedPackageDigest)
                && validDigest(version.getPackageDigest())
                && (admittedRun || (Objects.equals(version.getStatus(), NORMAL)
                    && Objects.equals(version.getDelFlag(), NORMAL)))
                && relationCount == 0L && SkillModelConfiguration.from(version).isValid();
        if (!ready) {
            throw new ServiceException("Assistant Skill fixed method is unavailable");
        }
        // Recovery uses the admitted immutable contract; only new admission applies this gate.
        if (!admittedRun && "TEXT".equalsIgnoreCase(SkillModelService.outputKind(version))) {
            try {
                SkillMethodInputContract.objectParameters(version.getInputSchemaJson());
            } catch (RuntimeException invalid) {
                throw new ServiceException("Skill方法输入契约不可用：" + invalid.getMessage());
            }
            try {
                SkillMethodOutputContract.structured(version.getDefinitionJson(), version.getOutputSchemaJson());
            } catch (RuntimeException invalid) {
                throw new ServiceException("Skill方法输出契约不可用：" + invalid.getMessage());
            }
        }
        return new ReadyEntrypoint(version, Map.of());
    }

    private SkillPackageResourceLoader.AuthorizedPackage loadAuthorizedMethodVersion(
            AidSkill identity, Long expectedVersionId, Long requesterUserId, boolean admittedRun) {
        if (admittedRun || !PLATFORM.equals(identity.getOwnerType())) {
            return packageResourceLoader.loadAuthorizedVersion(
                    identity.getSkillCode(), expectedVersionId, requesterUserId, admittedRun);
        }
        return packageResourceLoader.loadAuthorizedFixedPublicPlatformVersion(
                identity.getSkillCode(), expectedVersionId);
    }

    private Map<Long, ReadyEntrypoint> resolve(List<AidSkill> identities) {
        if (identities == null || identities.isEmpty()) {
            return Map.of();
        }
        List<AidSkill> candidates = identities.stream().filter(this::validRootIdentity).toList();
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Set<Long> rootVersionIds = candidates.stream().map(AidSkill::getCurrentVersionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, AidSkillVersion> rootVersions = loadVersions(rootVersionIds);
        Set<Long> validRootVersionIds = candidates.stream()
                .filter(identity -> validRootVersion(identity, rootVersions.get(identity.getCurrentVersionId())))
                .map(AidSkill::getCurrentVersionId).collect(Collectors.toSet());
        if (validRootVersionIds.isEmpty()) {
            return Map.of();
        }

        List<AidSkillRelation> relations = relationMapper.selectList(Wrappers.<AidSkillRelation>lambdaQuery()
                .in(AidSkillRelation::getParentVersionId, validRootVersionIds)
                .eq(AidSkillRelation::getRelationType, CHILD)
                .eq(AidSkillRelation::getDelFlag, NORMAL)
                .orderByAsc(AidSkillRelation::getParentVersionId)
                .orderByAsc(AidSkillRelation::getId));
        Map<Long, List<AidSkillRelation>> relationsByRoot = relations.stream()
                .collect(Collectors.groupingBy(AidSkillRelation::getParentVersionId));
        Set<Long> childSkillIds = relations.stream().map(AidSkillRelation::getChildSkillId)
                .collect(Collectors.toSet());
        Set<Long> childVersionIds = relations.stream().map(AidSkillRelation::getChildVersionId)
                .collect(Collectors.toSet());
        Map<Long, AidSkill> children = childSkillIds.isEmpty() ? Map.of()
                : skillMapper.selectList(Wrappers.<AidSkill>lambdaQuery()
                        .select(AidSkill::getId, AidSkill::getSkillCode, AidSkill::getOwnerType,
                                AidSkill::getVisibility, AidSkill::getInvocationScope, AidSkill::getStatus,
                                AidSkill::getDelFlag)
                        .in(AidSkill::getId, childSkillIds)).stream()
                .collect(Collectors.toMap(AidSkill::getId, Function.identity()));
        Map<Long, AidSkillVersion> childVersions = loadVersions(childVersionIds);

        Set<String> modelCodes = rootVersions.values().stream()
                .flatMap(version -> SkillModelConfiguration.from(version).selectableModelCodes().stream())
                .collect(Collectors.toSet());
        Set<String> availableModels = skillModelService.activeModels(modelCodes).keySet();

        Map<Long, ReadyEntrypoint> result = new LinkedHashMap<>();
        for (AidSkill identity : candidates) {
            AidSkillVersion rootVersion = rootVersions.get(identity.getCurrentVersionId());
            SkillModelConfiguration modelConfig = SkillModelConfiguration.from(rootVersion);
            if (!validRootVersion(identity, rootVersion)
                    || modelConfig.selectableModelCodes().stream().noneMatch(availableModels::contains)) {
                continue;
            }
            Map<String, ReadyChild> readyChildren = resolveRequiredChildren(identity.getSkillCode(),
                    relationsByRoot.getOrDefault(rootVersion.getId(), List.of()), children, childVersions);
            if (readyChildren == null) {
                continue;
            }
            Map<String, AidSkillVersion> packages = new LinkedHashMap<>();
            packages.put(identity.getSkillCode(), rootVersion);
            readyChildren.forEach((code, child) -> packages.put(code, child.version()));
            try {
                packageResourceLoader.verifyVersionsCached(packages);
                result.put(identity.getId(), new ReadyEntrypoint(rootVersion, Map.copyOf(readyChildren)));
            } catch (RuntimeException error) {
                log.error("Skill Runtime入口版本包不可用, skillId={}, skillCode={}, errorType={}",
                        identity.getId(), identity.getSkillCode(), error.getClass().getSimpleName(), error);
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, ReadyChild> resolveRequiredChildren(String rootCode, List<AidSkillRelation> relations,
                                                             Map<Long, AidSkill> children,
                                                             Map<Long, AidSkillVersion> childVersions) {
        Set<String> requiredCodes = REQUIRED_CHILDREN.get(rootCode);
        if (requiredCodes == null) {
            return null;
        }
        Map<String, AidSkillRelation> byKey = relations.stream()
                .filter(relation -> requiredCodes.contains(relation.getRelationKey()))
                .collect(Collectors.toMap(AidSkillRelation::getRelationKey, Function.identity(), (left, right) -> left));
        Map<String, ReadyChild> result = new LinkedHashMap<>();
        for (String childCode : requiredCodes) {
            AidSkillRelation relation = byKey.get(childCode);
            AidSkill child = relation == null ? null : children.get(relation.getChildSkillId());
            AidSkillVersion version = relation == null ? null : childVersions.get(relation.getChildVersionId());
            if (relation == null || !Boolean.TRUE.equals(relation.getRequiredFlag())
                    || !validChild(childCode, child, version)) {
                return null;
            }
            result.put(childCode, new ReadyChild(child, version));
        }
        return result;
    }

    private boolean validRootIdentity(AidSkill identity) {
        return identity != null && identity.getId() != null
                && SkillRuntimeCapabilities.supports(identity.getSkillCode())
                && PLATFORM.equals(identity.getOwnerType())
                && PUBLIC.equals(identity.getVisibility())
                && ENTRYPOINT.equals(identity.getInvocationScope())
                && NORMAL.equals(identity.getStatus()) && NORMAL.equals(identity.getDelFlag())
                && identity.getCurrentVersionId() != null;
    }

    private boolean validRootVersion(AidSkill identity, AidSkillVersion version) {
        return version != null && Objects.equals(version.getSkillId(), identity.getId())
                && PUBLIC.equals(version.getVisibility()) && ENTRYPOINT.equals(version.getInvocationScope())
                && PUBLISHED.equals(version.getPublishStatus())
                && ORCHESTRATOR.equals(version.getExecutorType()) && validDigest(version.getPackageDigest())
                && SkillModelConfiguration.from(version).isValid()
                && NORMAL.equals(version.getStatus()) && NORMAL.equals(version.getDelFlag());
    }

    private boolean validChild(String childCode, AidSkill child, AidSkillVersion version) {
        return child != null && version != null && childCode.equals(child.getSkillCode())
                && PLATFORM.equals(child.getOwnerType()) && validChildVisibility(child, version)
                && INTERNAL.equals(child.getInvocationScope())
                && NORMAL.equals(child.getStatus()) && NORMAL.equals(child.getDelFlag())
                && Objects.equals(version.getSkillId(), child.getId())
                && INTERNAL.equals(version.getInvocationScope())
                && PROMPT.equals(version.getExecutorType())
                && validDigest(version.getPackageDigest())
                && NORMAL.equals(version.getStatus()) && NORMAL.equals(version.getDelFlag());
    }

    private boolean validChildVisibility(AidSkill child, AidSkillVersion version) {
        // Publication visibility and INTERNAL invocation scope are independent.
        // Existing fixed private versions remain usable when an identity is made public.
        if (PRIVATE.equals(version.getVisibility())) {
            return PRIVATE.equals(child.getVisibility()) || PUBLIC.equals(child.getVisibility());
        }
        return PUBLIC.equals(version.getVisibility()) && PUBLIC.equals(child.getVisibility())
                && "PUBLISHED".equals(version.getPublishStatus());
    }

    private boolean validDigest(String digest) {
        return digest != null && digest.matches("[0-9a-f]{64}");
    }

    private Map<Long, AidSkillVersion> loadVersions(Set<Long> versionIds) {
        return packageResourceLoader.loadVersions(versionIds);
    }

    public record ReadyEntrypoint(AidSkillVersion rootVersion, Map<String, ReadyChild> children) { }

    public record ReadyChild(AidSkill identity, AidSkillVersion version) { }
}
