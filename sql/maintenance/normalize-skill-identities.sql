SET NAMES utf8mb4;
-- Skill 身份规范升级：现有库先停服执行 tools/maintenance/normalize_skill_identities.py。
-- 不提供旧编码别名。禁止单独 UPDATE skill_code，避免损坏版本摘要及 Agent 冻结绑定。
DELIMITER $$
DROP PROCEDURE IF EXISTS aid_require_normalized_skill_identity$$
CREATE PROCEDURE aid_require_normalized_skill_identity()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='aid_skill') THEN
    IF EXISTS (SELECT 1 FROM aid_skill WHERE del_flag='0' AND skill_code IN ('aid-screenwriter-craft','aid-screenwriter-adapt','aid-screenwriter-review','aid-method-automotive-sensory-ad','aid-method-branching-boxing-drama','aid-method-character-turnaround','aid-method-character-wardrobe','aid-method-cinematic-travelogue','aid-method-classic-wuxia-direction','aid-method-combat-motion','aid-method-eastern-atmosphere','aid-method-feminine-character','aid-method-frontier-duel-cinema','aid-method-gameplay-promo','aid-method-gothic-power-drama','aid-method-grounded-fight-choreography','aid-method-immersive-one-take','aid-method-manga-hero','aid-method-manga-heroine','aid-method-modern-oriental-ad','aid-method-observational-family-cinema','aid-method-photoreal-restoration','aid-method-reference-video-remake','aid-method-romance-fantasy-series','aid-method-script-character-visuals','aid-method-story-led-commercial','aid-method-symmetry-color-cinema','aid-method-youthful-character-look','character-card-image','character-turnaround-image','character-face-turnaround-image','character-form-image','storyboard-scene-image','storyboard-prop-image','storyboard-nine-grid','storyboard-four-grid','storyboard-25-grid','storyboard-light-correction','storyboard-time-forward','storyboard-time-backward','screenplay-write','screenplay-review')) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Stop backend and run normalize_skill_identities.py before this SQL';
    END IF;
  END IF;
END$$
CALL aid_require_normalized_skill_identity()$$
DROP PROCEDURE aid_require_normalized_skill_identity$$
DELIMITER ;

-- 默认平台名称使用中文，不覆盖用户后续维护的名称。
UPDATE aid_skill SET name='原创编剧',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_screenwriter_craft' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='剧本改编',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_screenwriter_adapt' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='剧本审稿',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_screenwriter_review' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='汽车感官广告',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_automotive_sensory_ad' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='拳击分支剧情',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_branching_boxing_drama' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='角色设定与三视图',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_character_turnaround' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='角色妆发与服装设计',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_character_wardrobe' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='电影感旅拍',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_cinematic_travelogue' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='武侠创作与导演',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_classic_wuxia_direction' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='打斗动作视频',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_combat_motion' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='东方电影氛围',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_eastern_atmosphere' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='女性角色设计',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_feminine_character' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='荒野决斗电影',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_frontier_duel_cinema' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='游戏体验宣传片',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_gameplay_promo' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='哥特权力短剧',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_gothic_power_drama' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='写实打斗编排',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_grounded_fight_choreography' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='沉浸式一镜到底',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_immersive_one_take' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='漫画男性主角设计',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_manga_hero' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='漫画女性主角设计',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_manga_heroine' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='当代东方广告',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_modern_oriental_ad' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='家庭生活观察电影',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_observational_family_cinema' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='照片级修复与重建',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_photoreal_restoration' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='参考视频分析与重制',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_reference_video_remake' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='情感奇幻短剧',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_romance_fantasy_series' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='剧本人物视觉设计',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_script_character_visuals' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='叙事广告创作',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_story_led_commercial' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='对称构图与色彩叙事',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_symmetry_color_cinema' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='清新生活化角色设计',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='aid_method_youthful_character_look' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='角色设定卡图',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='character_card_image' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='角色三视图',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='character_turnaround_image' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='面部三视图',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='character_face_turnaround_image' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='角色其他形态图',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='character_form_image' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='场景设定图',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='storyboard_scene_image' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='道具设定图',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='storyboard_prop_image' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='多机位九宫格',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='storyboard_nine_grid' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='剧情推演四宫格',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='storyboard_four_grid' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='25宫格连贯分镜',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='storyboard_25_grid' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='电影级光影校正',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='storyboard_light_correction' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='画面推演3秒后',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='storyboard_time_forward' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='画面推演5秒前',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='storyboard_time_backward' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='剧本写作',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='screenplay_write' AND name REGEXP '^[A-Za-z]';
UPDATE aid_skill SET name='剧本审核',update_by='skill_identity_migration',update_time=NOW() WHERE owner_type='PLATFORM' AND del_flag='0' AND skill_code='screenplay_review' AND name REGEXP '^[A-Za-z]';

-- Fresh seed bodies use the canonical root identity. Recompute only a current,
-- resource-free V1 seed when its digest differs; an already-correct package is untouched.
UPDATE aid_skill_version v JOIN aid_skill s ON s.current_version_id=v.id AND s.id=v.skill_id
SET v.package_digest=SHA2(CONCAT(
  '{"format":"aid-db-package-v1"',
  IF(s.skill_code IS NULL,'',CONCAT(',"skillCode":',JSON_QUOTE(s.skill_code))),
  IF(v.version_code IS NULL,'',CONCAT(',"versionCode":',JSON_QUOTE(v.version_code))),
  IF(v.visibility IS NULL,'',CONCAT(',"visibility":',JSON_QUOTE(v.visibility))),
  IF(v.invocation_scope IS NULL,'',CONCAT(',"invocationScope":',JSON_QUOTE(v.invocation_scope))),
  IF(v.executor_type IS NULL,'',CONCAT(',"executorType":',JSON_QUOTE(v.executor_type))),
  IF(v.model_code IS NULL,'',CONCAT(',"modelCode":',JSON_QUOTE(v.model_code))),
  CONCAT(',"systemPromptDigest":"',SHA2(COALESCE(v.system_prompt,''),256),'"'),
  CONCAT(',"inputSchemaDigest":"',SHA2(COALESCE(v.input_schema_json,''),256),'"'),
  CONCAT(',"outputSchemaDigest":"',SHA2(COALESCE(v.output_schema_json,''),256),'"'),
  CONCAT(',"definitionDigest":"',SHA2(COALESCE(v.definition_json,''),256),'"'),
  IF(v.max_output_tokens IS NULL,'',CONCAT(',"maxOutputTokens":',v.max_output_tokens)),
  IF(v.context_window_tokens IS NULL,'',CONCAT(',"contextWindowTokens":',v.context_window_tokens)),
  IF(v.safety_margin_tokens IS NULL,'',CONCAT(',"safetyMarginTokens":',v.safety_margin_tokens)),
  ',"resources":[],"relations":[]}'
),256),v.update_by='skill_identity_migration',v.update_time=NOW()
WHERE s.skill_code IN ('aid_screenwriter_craft','aid_screenwriter_adapt','aid_screenwriter_review','aid_method_automotive_sensory_ad','aid_method_branching_boxing_drama','aid_method_character_turnaround','aid_method_character_wardrobe','aid_method_cinematic_travelogue','aid_method_classic_wuxia_direction','aid_method_combat_motion','aid_method_eastern_atmosphere','aid_method_feminine_character','aid_method_frontier_duel_cinema','aid_method_gameplay_promo','aid_method_gothic_power_drama','aid_method_grounded_fight_choreography','aid_method_immersive_one_take','aid_method_manga_hero','aid_method_manga_heroine','aid_method_modern_oriental_ad','aid_method_observational_family_cinema','aid_method_photoreal_restoration','aid_method_reference_video_remake','aid_method_romance_fantasy_series','aid_method_script_character_visuals','aid_method_story_led_commercial','aid_method_symmetry_color_cinema','aid_method_youthful_character_look','character_card_image','character_turnaround_image','character_face_turnaround_image','character_form_image','storyboard_scene_image','storyboard_prop_image','storyboard_nine_grid','storyboard_four_grid','storyboard_25_grid','storyboard_light_correction','storyboard_time_forward','storyboard_time_backward','screenplay_write','screenplay_review') AND s.owner_type='PLATFORM' AND s.del_flag='0' AND v.del_flag='0' AND v.status='0'
  AND JSON_UNQUOTE(JSON_EXTRACT(v.manifest_json,'$.digestAlgorithm'))='aid-db-package-v1'
  AND NOT EXISTS (SELECT 1 FROM aid_skill_resource r WHERE r.skill_version_id=v.id AND r.status='0' AND r.del_flag='0')
  AND NOT EXISTS (SELECT 1 FROM aid_skill_relation r WHERE r.parent_version_id=v.id AND r.del_flag='0')
  AND v.package_digest<>SHA2(CONCAT(
  '{"format":"aid-db-package-v1"',
  IF(s.skill_code IS NULL,'',CONCAT(',"skillCode":',JSON_QUOTE(s.skill_code))),
  IF(v.version_code IS NULL,'',CONCAT(',"versionCode":',JSON_QUOTE(v.version_code))),
  IF(v.visibility IS NULL,'',CONCAT(',"visibility":',JSON_QUOTE(v.visibility))),
  IF(v.invocation_scope IS NULL,'',CONCAT(',"invocationScope":',JSON_QUOTE(v.invocation_scope))),
  IF(v.executor_type IS NULL,'',CONCAT(',"executorType":',JSON_QUOTE(v.executor_type))),
  IF(v.model_code IS NULL,'',CONCAT(',"modelCode":',JSON_QUOTE(v.model_code))),
  CONCAT(',"systemPromptDigest":"',SHA2(COALESCE(v.system_prompt,''),256),'"'),
  CONCAT(',"inputSchemaDigest":"',SHA2(COALESCE(v.input_schema_json,''),256),'"'),
  CONCAT(',"outputSchemaDigest":"',SHA2(COALESCE(v.output_schema_json,''),256),'"'),
  CONCAT(',"definitionDigest":"',SHA2(COALESCE(v.definition_json,''),256),'"'),
  IF(v.max_output_tokens IS NULL,'',CONCAT(',"maxOutputTokens":',v.max_output_tokens)),
  IF(v.context_window_tokens IS NULL,'',CONCAT(',"contextWindowTokens":',v.context_window_tokens)),
  IF(v.safety_margin_tokens IS NULL,'',CONCAT(',"safetyMarginTokens":',v.safety_margin_tokens)),
  ',"resources":[],"relations":[]}'
),256);
UPDATE aid_skill s JOIN aid_skill_version v ON v.id=s.current_version_id AND v.skill_id=s.id
SET s.config_hash=SHA2(CONCAT(s.skill_code,'|',v.version_code,'|',v.package_digest),256),
    s.update_by='skill_identity_migration',s.update_time=NOW()
WHERE s.skill_code IN ('aid_screenwriter_craft','aid_screenwriter_adapt','aid_screenwriter_review','aid_method_automotive_sensory_ad','aid_method_branching_boxing_drama','aid_method_character_turnaround','aid_method_character_wardrobe','aid_method_cinematic_travelogue','aid_method_classic_wuxia_direction','aid_method_combat_motion','aid_method_eastern_atmosphere','aid_method_feminine_character','aid_method_frontier_duel_cinema','aid_method_gameplay_promo','aid_method_gothic_power_drama','aid_method_grounded_fight_choreography','aid_method_immersive_one_take','aid_method_manga_hero','aid_method_manga_heroine','aid_method_modern_oriental_ad','aid_method_observational_family_cinema','aid_method_photoreal_restoration','aid_method_reference_video_remake','aid_method_romance_fantasy_series','aid_method_script_character_visuals','aid_method_story_led_commercial','aid_method_symmetry_color_cinema','aid_method_youthful_character_look','character_card_image','character_turnaround_image','character_face_turnaround_image','character_form_image','storyboard_scene_image','storyboard_prop_image','storyboard_nine_grid','storyboard_four_grid','storyboard_25_grid','storyboard_light_correction','storyboard_time_forward','storyboard_time_backward','screenplay_write','screenplay_review') AND s.owner_type='PLATFORM' AND s.del_flag='0'
  AND JSON_UNQUOTE(JSON_EXTRACT(v.manifest_json,'$.digestAlgorithm'))='aid-db-package-v1'
  AND s.config_hash<>SHA2(CONCAT(s.skill_code,'|',v.version_code,'|',v.package_digest),256);
