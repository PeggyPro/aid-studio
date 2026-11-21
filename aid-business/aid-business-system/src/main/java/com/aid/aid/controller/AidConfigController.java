package com.aid.aid.controller;

import java.util.List;
import java.util.Objects;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.aid.domain.AidConfig;
import com.aid.aid.service.IAidConfigService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

import cn.hutool.core.util.StrUtil;

/**
 * 配置信息Controller
 *
 * @author 视觉AID
 */
@RestController
@RequestMapping("/aidconfig/aidconfig")
public class AidConfigController extends BaseController
{
    @Autowired
    private IAidConfigService aidConfigService;

    @Autowired
    private com.aid.common.aid.core.service.RealtimeOriginSettings realtimeOrigins;

    @org.springframework.beans.factory.annotation.Value("${canvas.websocket.allowed-origins:}")
    private String realtimeEnvironmentDefault;

    /** Read the effective policy without exposing deployment environment variables. */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:list')")
    @GetMapping("/realtime-origins")
    public AjaxResult realtimeOrigins()
    {
        return success(java.util.Map.of("origins", realtimeOrigins.editorOrigins(realtimeEnvironmentDefault),
                "cacheSeconds", 5));
    }

    /**
     * 查询配置信息列表
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidConfig aidConfig)
    {
        startPage();
        aidConfig.setConfigValue(null);
        List<AidConfig> list = aidConfigService.selectAidConfigList(aidConfig);
        // 列表展示脱敏：密钥/令牌类配置值打码，避免 AK/SK、私钥、token 在后台列表明文泄漏
        for (AidConfig item : list) {
            maskSecretInPlace(item);
        }
        return getDataTable(list);
    }

    /**
     * 导出配置信息列表
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:export')")
    @Log(title = "配置信息", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidConfig aidConfig)
    {
        aidConfig.setConfigValue(null);
        List<AidConfig> list = aidConfigService.selectAidConfigList(aidConfig);
        list.forEach(this::maskSecretInPlace);
        ExcelUtil<AidConfig> util = new ExcelUtil<AidConfig>(AidConfig.class);
        util.exportExcel(response, list, "配置信息数据");
    }

    /**
     * 获取配置信息详细信息
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        AidConfig config = aidConfigService.selectAidConfigById(id);
        maskSecretInPlace(config);
        return success(config);
    }

    /**
     * 新增配置信息
     * 配置项支持后台 UI 维护；密钥/令牌类的展示脱敏与回写保护见 list / edit。
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:add')")
    @Log(title = "配置信息", businessType = BusinessType.INSERT, isSaveRequestData = false)
    @PostMapping
    public AjaxResult add(@RequestBody AidConfig aidConfig)
    {
        return toAjax(aidConfigService.insertAidConfig(aidConfig));
    }

    /**
     * 修改配置信息
     * 脱敏回写保护：若提交的 configValue 与该字段当前值的脱敏形态一致，说明本项未改，
     * 保留 DB 原值，兼容升级前已打开的配置页面。
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @Log(title = "配置信息", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @PutMapping
    public AjaxResult edit(@RequestBody AidConfig aidConfig)
    {
        if (aidConfig != null && aidConfig.getId() != null) {
            // 按 id 回查 DB 真实值，判断密钥类字段是否被"原样回写打码串"
            AidConfig existing = aidConfigService.selectAidConfigById(aidConfig.getId());
            if (existing != null && isSecretConfig(existing.getConfigName())) {
                // 保留敏感配置原有分类，避免通过改名使其明文出现在查询结果中。
                aidConfig.setConfigName(existing.getConfigName());
                if (isMaskedSecretSubmission(aidConfig.getConfigValue(), existing.getConfigValue())) {
                    // 提交值=脱敏串 → 视为未修改，保留原密钥，避免误冲
                    aidConfig.setConfigValue(existing.getConfigValue());
                }
            }
        }
        return toAjax(aidConfigService.updateAidConfig(aidConfig));
    }

    /**
     * 删除配置信息
     */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:remove')")
    @Log(title = "配置信息", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aidConfigService.deleteAidConfigByIds(ids));
    }

    /**
     * 密钥/令牌类配置 config_name 关键字：命中则列表展示脱敏 + 编辑回写保护。
     * 仅按字段名判定，不涉及业务参数（如 imageUrlWhitelist / uploadMode / cdnDomain 等照常明文可改）。
     */
    private static final java.util.List<String> SECRET_NAME_KEYWORDS = java.util.List.of(
            "secret", "password", "passwd", "pwd", "accesskey", "apikey", "api_key",
            "privatekey", "private_key", "token", "appsecret", "signkey", "mchkey",
            "encodingaeskey", "encoding_aes_key", "aeskey", "aes_key", "access_key",
            "access_code", "accesscode", "sign_key", "api-key", "credential"
    );

    /**
     * 判断配置项是否为密钥/令牌类（按 config_name 关键字，大小写不敏感）。
     *
     * @param configName 配置名
     * @return true=密钥类，需脱敏 / 回写保护
     */
    private boolean isSecretConfig(String configName)
    {
        if (StrUtil.isBlank(configName)) {
            return false;
        }
        String lower = configName.trim().toLowerCase(java.util.Locale.ROOT);
        for (String kw : SECRET_NAME_KEYWORDS) {
            if (lower.contains(kw)) {
                return true; // 命中密钥关键字
            }
        }
        return false;
    }

    private static final String MASKED_SECRET = "********";

    private String maskSecretValue(String value)
    {
        return StrUtil.isBlank(value) ? value : MASKED_SECRET;
    }

    private boolean isMaskedSecretSubmission(String submitted, String stored)
    {
        if (Objects.equals(submitted, maskSecretValue(stored))) {
            return true;
        }
        if (StrUtil.isBlank(stored)) {
            return false;
        }
        // Older edit pages may still submit the display value they loaded before the upgrade.
        String legacyMask = stored.length() > 8
                ? stored.substring(0, 4) + "****" + stored.substring(stored.length() - 4)
                : "****";
        return Objects.equals(submitted, legacyMask);
    }

    /**
     * 列表项脱敏：命中密钥类则对 configValue 打码（原对象就地替换，仅影响展示）。
     *
     * @param item 配置项
     */
    private void maskSecretInPlace(AidConfig item)
    {
        if (item != null && isSecretConfig(item.getConfigName())) {
            item.setConfigValue(maskSecretValue(item.getConfigValue()));
        }
    }
}
