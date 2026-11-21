package com.aid.skill.service;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.skill.dto.SkillPackageAdminRequests;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads reviewed built-in method packages without writing Skill identities or versions. */
@Component
public class BuiltinSkillPackageCatalog
{
    public static final String VERSION = "1.0.0";
    private static final List<String> CODES = List.of(
            "aid_screenwriter_craft", "aid_screenwriter_adapt", "aid_screenwriter_review",
            "aid_method_automotive_sensory_ad", "aid_method_branching_boxing_drama",
            "aid_method_character_turnaround", "aid_method_character_wardrobe",
            "aid_method_cinematic_travelogue", "aid_method_classic_wuxia_direction",
            "aid_method_combat_motion", "aid_method_eastern_atmosphere",
            "aid_method_feminine_character", "aid_method_frontier_duel_cinema",
            "aid_method_gameplay_promo", "aid_method_gothic_power_drama",
            "aid_method_grounded_fight_choreography", "aid_method_immersive_one_take",
            "aid_method_manga_hero", "aid_method_manga_heroine",
            "aid_method_modern_oriental_ad", "aid_method_observational_family_cinema",
            "aid_method_photoreal_restoration", "aid_method_reference_video_remake",
            "aid_method_romance_fantasy_series", "aid_method_script_character_visuals",
            "aid_method_story_led_commercial", "aid_method_symmetry_color_cinema",
            "aid_method_youthful_character_look");

    private final Map<String, Definition> definitions;

    public BuiltinSkillPackageCatalog()
    {
        Map<String, Definition> loaded = new LinkedHashMap<>();
        for (String code : CODES) loaded.put(code, load(code));
        definitions = Map.copyOf(loaded);
    }

    public List<String> codes() { return CODES; }
    public List<Definition> definitions() { return CODES.stream().map(definitions::get).toList(); }
    public Definition definition(String code)
    {
        Definition definition = definitions.get(code);
        if (definition == null) throw unavailable(code);
        return definition;
    }

    public SkillPackageAdminRequests.DraftSaveRequest draft(Definition definition, Long skillId,
                                                             String modelCode)
    {
        SkillPackageAdminRequests.DraftSaveRequest result = new SkillPackageAdminRequests.DraftSaveRequest();
        result.setSkillId(skillId);
        result.setModelCode(modelCode);
        result.setDefaultModelCode(modelCode);
        result.setSelectableModelCodes(List.of(modelCode));
        result.setSystemPrompt(definition.systemPrompt());
        result.setInputSchemaJson("{\"type\":\"object\"}");
        result.setOutputSchemaJson("{\"type\":\"object\"}");
        JSONObject source = new JSONObject();
        source.put("code", definition.code());
        source.put("version", definition.version());
        source.put("digest", definition.sourceDigest());
        result.setDefinitionJson(JSON.toJSONString(Map.of("builtinSource", source)));
        result.setMaxOutputTokens(8192);
        result.setContextWindowTokens(128000);
        result.setSafetyMarginTokens(8192);
        result.setResources(definition.resources().stream().map(resource -> {
            SkillPackageAdminRequests.ResourceItem item = new SkillPackageAdminRequests.ResourceItem();
            item.setResourceKey(resource.key());
            item.setResourceType("REFERENCE");
            item.setMimeType(resource.path().endsWith(".md") ? "text/markdown" : "text/plain");
            item.setContent(resource.content());
            item.setRouteJson(resource.always() ? "{\"always\":true}" : "{}");
            return item;
        }).toList());
        result.setRelations(List.of());
        return result;
    }

    private Definition load(String expectedCode)
    {
        String root = "skills/" + expectedCode + "/" + VERSION + "/";
        String manifestText = read(root + "aid-skill.json");
        JSONObject manifest;
        try { manifest = JSON.parseObject(manifestText); }
        catch (RuntimeException invalid) { throw unavailable(expectedCode); }
        if (manifest == null || !expectedCode.equals(manifest.getString("code"))
                || !VERSION.equals(manifest.getString("version"))
                || !"INTERNAL".equals(manifest.getString("invocationScope"))
                || !"PROMPT".equals(manifest.getString("executorType"))
                || blank(manifest.getString("name")) || blank(manifest.getString("systemPrompt")))
            throw unavailable(expectedCode);
        JSONArray resources = manifest.getJSONArray("resources");
        if (resources == null || resources.isEmpty() || resources.size() > 64) throw unavailable(expectedCode);
        List<Resource> loaded = new ArrayList<>();
        StringBuilder digestBasis = new StringBuilder(manifestText);
        for (Object raw : resources)
        {
            if (!(raw instanceof JSONObject item)) throw unavailable(expectedCode);
            String key = item.getString("key");
            String path = item.getString("path");
            String digest = item.getString("digest");
            if (blank(key) || !key.matches("[a-z0-9][a-z0-9._-]{0,99}") || blank(path)
                    || path.startsWith("/") || path.contains("..")
                    || blank(digest) || !digest.matches("[0-9a-f]{64}")) throw unavailable(expectedCode);
            String content = read(root + path);
            if (!Objects.equals(digest, SecureUtil.sha256(content))) throw unavailable(expectedCode);
            boolean always = Boolean.TRUE.equals(item.getBoolean("always"));
            loaded.add(new Resource(key, path, content, digest, always));
            digestBasis.append('\n').append(key).append('\n').append(path).append('\n').append(digest);
        }
        return new Definition(expectedCode, VERSION, manifest.getString("name"),
                manifest.getString("systemPrompt"), SecureUtil.sha256(digestBasis.toString()), List.copyOf(loaded));
    }

    private String read(String path)
    {
        try (InputStream input = new ClassPathResource(path).getInputStream())
        {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException error)
        {
            throw new ServiceException("内置Skill资源不可用").setDetailMessage(path);
        }
    }

    private ServiceException unavailable(String code)
    {
        return new ServiceException("内置Skill资源无效").setDetailMessage(code);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record Definition(String code, String version, String name, String systemPrompt,
                             String sourceDigest, List<Resource> resources) { }
    public record Resource(String key, String path, String content, String digest, boolean always) { }
}
