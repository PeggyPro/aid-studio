package com.aid.script.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 剧本分集预览入参。
 *
 * @author 视觉AID
 */
@Data
public class ScriptSplitPreviewRequest {
    /** 确认导入时核对预览的起始集号。 */
    private Long firstEpisodeNo;

    /** 项目ID（必填，必须是剧集类型项目） */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /**
     * 整篇剧本文本（必填，业务字数上限由后台配置）。
     * 来源：用户在导入框粘贴的整篇剧本，或前端读取 .txt 文件后的文本内容。
     */
    @NotBlank(message = "剧本内容不能为空")
    @Size(max = 4_000_000, message = "剧本文本过大")
    private String scriptText;

    /**
     * 分集词样例（可空，默认「第一集」）。
     * 含序数的样例（如「第一集」「第1话」）自动泛化：「第一集/第2集/第十三集」均可命中；
     * 不含序数的样例（如「===分集===」）按行首字面量匹配。
     */
    @Size(max = 100, message = "分集词过长")
    private String episodeKeyword;
}
