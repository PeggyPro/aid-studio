package com.aid.generation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 通用生成入口的受控请求对象。 */
public final class GenerationRequests {
    private GenerationRequests() { }

    @Data
    public abstract static class Base {
        @Size(max = 100, message = "模型编码过长")
        @Pattern(regexp = ".*\\S.*", flags = Pattern.Flag.DOTALL, message = "模型编码不能为空白")
        private String modelName;
        @Size(max = 96, message = "能力编码过长")
        @Pattern(regexp = "[a-z][a-z0-9_-]{0,95}", message = "能力编码格式错误")
        private String capabilityCode;
        @Size(max = 96, message = "功能编码过长")
        @Pattern(regexp = "[a-z][a-z0-9._-]{0,95}", message = "功能编码格式错误")
        private String funcCode;
        @Min(value = 1, message = "项目ID错误")
        private Long projectId;
        @Min(value = 0, message = "剧集ID错误")
        private Long episodeId;
        @Min(value = 1, message = "工作区ID错误")
        private Long workspaceId;
        @NotBlank(message = "请求标识必填")
        @Size(max = 64, message = "请求标识过长")
        @Pattern(regexp = "[A-Za-z0-9._:-]+", message = "请求标识格式错误")
        private String clientRequestId;
        @Valid
        private Session session;
        @Valid
        private CanvasNode canvasNode;
        @Size(max = 64, message = "参数数量过多")
        private Map<String, Object> parameters = new LinkedHashMap<>();
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Text extends Base {
        @Size(max = 100000, message = "提示词过长")
        private String prompt;
        @Size(max = 100, message = "消息数量过多")
        private List<@Valid Message> messages = new ArrayList<>();
        @Size(max = 32, message = "素材数量过多")
        private List<@Valid ResourceReference> references = new ArrayList<>();
        @Size(max = 20, message = "节点引用过多")
        private List<@Valid CanvasTextReference> nodeReferences = new ArrayList<>();
        private Boolean stream = Boolean.FALSE;
        @Pattern(regexp = "TEXT|MARKDOWN|HTML", message = "结果格式错误")
        private String resultFormat = "TEXT";
        private Boolean reasoningEnabled;
        @Size(max = 20, message = "思考档位过长")
        private String reasoningLevel;
        @Min(value = 1, message = "思考预算错误")
        @Max(value = 2000000, message = "思考预算过大")
        private Integer reasoningBudgetTokens;
        private Boolean includeReasoning;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Image extends Base {
        @Size(max = 100000, message = "提示词过长")
        private String prompt;
        @Size(max = 100000, message = "负向提示词过长")
        private String negativePrompt;
        @Size(max = 30, message = "图片尺寸过长")
        private String size;
        @Min(value = 1, message = "图片数量错误")
        @Max(value = 100, message = "图片数量过大")
        private Integer expectedImageCount;
        @Size(max = 32, message = "素材数量过多")
        private List<@Valid ResourceReference> references = new ArrayList<>();
        @Size(max = 20, message = "节点引用过多")
        private List<@Valid CanvasTextReference> nodeReferences = new ArrayList<>();
        @Valid
        private ImageEdit imageEdit;
    }

    @Data
    public static class ImageEdit {
        @NotBlank(message = "图片编辑操作必填")
        @Pattern(regexp = "REDRAW|ERASE|EXPAND|RELIGHT|LAYER_SPLIT|TOUCH_EDIT|EMOTION|PORTRAIT_TEXTURE|MULTI_VIEW|STYLE_APPLY", flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "图片编辑操作错误")
        private String operation;
        @NotNull(message = "原图资源必填")
        @Min(value = 1, message = "原图资源错误")
        private Long sourceResourceId;
        @Size(max = 64, message = "源节点ID过长")
        private String sourceObjectId;
        @Min(value = 1, message = "源节点版本错误")
        private Long sourceExpectedVersion;
        @Min(value = 1, message = "蒙版资源错误")
        private Long maskResourceId;
        @Pattern(regexp = "BLACK_WHITE|ALPHA", flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "蒙版语义错误")
        private String maskEncoding;
        @Valid
        private Expand expand;
        @Valid
        private Lighting lighting;
        @Size(max = 8, message = "编辑区域不能超过8个")
        private List<@Valid ImageRegion> selections;
        @Size(max = 8, message = "人脸区域不能超过8个")
        private List<@Valid FaceExpression> faces;
        @Valid
        private PortraitTexture portrait;
        @Valid
        private Camera camera;
        @Valid
        private Style style;
    }

    @Data
    public static class ImageRegion {
        @NotNull private Double x;
        @NotNull private Double y;
        @NotNull private Double width;
        @NotNull private Double height;
        @Size(max = 2000, message = "区域编辑要求过长") private String instruction;
    }

    @Data
    public static class FaceExpression {
        @Valid @NotNull private ImageRegion region;
        @NotBlank @Size(max = 80, message = "表情描述过长") private String expression;
    }

    @Data
    public static class PortraitTexture {
        @Min(-100) @Max(100) private Integer skinTexture;
        @Min(-100) @Max(100) private Integer surfaceTexture;
        @Min(-100) @Max(100) private Integer sharpness;
    }

    @Data
    public static class Camera {
        @NotNull @Min(0) @Max(359) private Integer yaw;
        @NotNull @Min(-90) @Max(90) private Integer pitch;
        @NotBlank @Pattern(regexp = "WIDE|MEDIUM|CLOSE") private String framing;
    }

    @Data
    public static class Style {
        @NotBlank @Pattern(regexp = "official|custom", flags = Pattern.Flag.CASE_INSENSITIVE)
        private String styleSource;
        @NotNull @Min(1) private Long styleAssetId;
    }

    @Data
    public static class Expand {
        @NotNull @Min(1) private Integer targetWidth;
        @NotNull @Min(1) private Integer targetHeight;
        @NotNull @Min(0) private Integer sourceX;
        @NotNull @Min(0) private Integer sourceY;
    }

    @Data
    public static class Lighting {
        @Min(-100) @Max(100) private Integer brightness;
        @Min(1000) @Max(20000) private Integer colorTemperature;
        @Min(0) @Max(359) private Integer keyLightAzimuth;
        @Min(-90) @Max(90) private Integer keyLightElevation;
        private Boolean rimLightEnabled;
        @Min(0) @Max(359) private Integer rimLightAzimuth;
        @Min(-90) @Max(90) private Integer rimLightElevation;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Video extends Base {
        @Size(max = 100000, message = "提示词过长")
        private String prompt;
        @Min(value = 1, message = "视频时长错误")
        @Max(value = 3600, message = "视频时长过大")
        private Integer durationSeconds;
        @Size(max = 30, message = "画幅比例过长")
        private String aspectRatio;
        private Boolean audio;
        private Boolean bgm;
        @Size(max = 30, message = "音频类型过长")
        private String audioType;
        @Size(max = 200, message = "音色标识过长")
        private String voiceId;
        @Size(max = 32, message = "素材数量过多")
        private List<@Valid ResourceReference> references = new ArrayList<>();
        @Size(max = 20, message = "节点引用过多")
        private List<@Valid CanvasTextReference> nodeReferences = new ArrayList<>();
    }

    @Data
    public static class Session {
        @Min(value = 1, message = "会话ID错误")
        private Long sessionId;
        @Size(max = 100, message = "会话标题过长")
        private String title;
    }

    @Data
    public static class CanvasNode {
        @NotBlank(message = "画布节点ID必填")
        @Size(max = 64, message = "画布节点ID过长")
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "画布节点ID格式错误")
        private String objectId;
        @NotNull(message = "画布节点版本必填")
        @Min(value = 1, message = "画布节点版本错误")
        private Long expectedVersion;
        @NotBlank(message = "画布编辑会话ID必填")
        @Size(max = 64, message = "画布编辑会话ID过长")
        private String editSessionId;
        @NotBlank(message = "画布节点锁令牌必填")
        @Size(max = 128, message = "画布节点锁令牌过长")
        private String lockToken;
    }

    @Data
    public static class CanvasTextReference {
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "引用节点ID格式错误")
        private String objectId;
        @NotNull @Min(1) private Long expectedVersion;
        @NotBlank @Pattern(regexp = "[\\p{L}\\p{N}_-]{1,32}", message = "引用别名格式错误")
        private String mention;
        @Min(0) private Integer selectionStart;
        @Min(0) private Integer selectionEnd;
    }

    @Data
    public static class Message {
        @NotBlank(message = "消息角色必填")
        @Pattern(regexp = "user|assistant|tool", flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "消息角色错误")
        private String role;
        @Size(max = 100000, message = "消息正文过长")
        private String content;
        @Size(max = 32, message = "消息内容块过多")
        private List<@Valid Part> parts = new ArrayList<>();
        @Size(max = 64, message = "工具调用标识过长")
        private String toolCallId;
    }

    @Data
    public static class Part {
        @NotBlank(message = "内容类型必填")
        @Pattern(regexp = "text|image|video|audio|document", flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "内容类型错误")
        private String type;
        @Size(max = 100000, message = "文本内容过长")
        private String text;
        @Min(value = 1, message = "资源ID错误")
        private Long resourceId;
        @Size(max = 100, message = "MIME类型过长")
        private String mimeType;
        @Size(max = 20, message = "图片精度过长")
        private String detail;
        @Size(max = 20, message = "解析精度过长")
        private String mediaResolution;
        @Min(value = 0, message = "抽帧频率错误")
        private Double fps;
        @Min(value = 0, message = "素材时长错误")
        private Double durationSeconds;
        @Min(value = 0, message = "文件大小错误")
        private Long sizeBytes;
        @Min(value = 0, message = "文档页数错误")
        private Integer pageCount;
        @Min(value = 0, message = "素材宽度错误")
        private Integer width;
        @Min(value = 0, message = "素材高度错误")
        private Integer height;
    }

    @Data
    public static class ResourceReference {
        @NotNull(message = "资源ID必填")
        @Min(value = 1, message = "资源ID错误")
        private Long resourceId;
        @NotBlank(message = "引用别名必填")
        @Size(max = 32, message = "引用别名过长")
        @Pattern(regexp = "[\\p{L}\\p{N}_-]+", message = "引用别名格式错误")
        private String mention;
        @NotBlank(message = "素材角色必填")
        @Pattern(regexp = "REFERENCE|FIRST_FRAME|LAST_FRAME", flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "素材角色错误")
        private String role;
    }

    @Data
    public static class HistoryQuery {
        @NotNull(message = "会话ID必填")
        @Min(value = 1, message = "会话ID错误")
        private Long sessionId;
        @Min(value = 1, message = "游标错误")
        private Long beforeSeq;
        @Min(value = 1, message = "分页数量错误")
        @Max(value = 100, message = "分页数量过大")
        private Integer limit = 20;
    }

    @Data
    public static class SessionListQuery {
        @Min(value = 1, message = "页码错误")
        private Integer pageNum = 1;
        @Min(value = 1, message = "分页数量错误")
        @Max(value = 100, message = "分页数量过大")
        private Integer pageSize = 20;
    }

    @Data
    public static class TaskStatusQuery {
        @NotNull(message = "任务ID必填")
        @Min(value = 1, message = "任务ID错误")
        private Long taskId;
    }
}
