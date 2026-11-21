package com.aid.canvas.model.mapper;

import com.aid.canvas.model.CanvasLibraryModel;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CanvasModelLibraryMapper {
    @Select("SELECT COUNT(*) FROM aid_canvas_model_category WHERE id=#{id} AND status='ACTIVE'") int activeCategory(String id);
    @Select("SELECT id,name,status,sort_order AS sortOrder FROM aid_canvas_model_category WHERE status<>'DELETED' ORDER BY sort_order,name LIMIT 200")
    List<Map<String,Object>> categories();
    @Select("SELECT id,name,status FROM aid_canvas_model_category WHERE id=#{id} FOR UPDATE") Map<String,Object> lockCategory(String id);
    @Insert("INSERT INTO aid_canvas_model_category(id,name,status,sort_order) VALUES(#{id},#{name},#{status},#{sort})")
    int insertCategory(@Param("id") String id,@Param("name") String name,@Param("status") String status,@Param("sort") int sort);
    @Update("UPDATE aid_canvas_model_category SET name=#{name},status=#{status},sort_order=#{sort} WHERE id=#{id}")
    int updateCategory(@Param("id") String id,@Param("name") String name,@Param("status") String status,@Param("sort") int sort);
    @Select("SELECT COUNT(*) FROM aid_canvas_model_library WHERE category_id=#{id} AND status<>'DELETED'") int categoryReferences(String id);
    @Select("SELECT m.*,c.name AS category_name FROM aid_canvas_model_library m JOIN aid_canvas_model_category c ON c.id=m.category_id WHERE m.id=#{id}") CanvasLibraryModel detail(String id);
    @Select("SELECT * FROM aid_canvas_model_library WHERE id=#{id} FOR UPDATE") CanvasLibraryModel lock(String id);
    @Select("SELECT * FROM aid_canvas_model_library WHERE content_hash=#{hash} AND status='PUBLISHED' ORDER BY create_time DESC,id DESC LIMIT 1") CanvasLibraryModel byHash(String hash);
    @Select("SELECT * FROM aid_canvas_model_library WHERE model_id=#{id} ORDER BY version_no DESC LIMIT 1 FOR UPDATE") CanvasLibraryModel latest(String id);
    @Select("<script>SELECT m.*,c.name AS category_name FROM aid_canvas_model_library m JOIN aid_canvas_model_category c ON c.id=m.category_id WHERE m.status&lt;&gt;'DELETED' <if test='publicOnly'>AND m.status='PUBLISHED' AND c.status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM aid_canvas_model_library newer WHERE newer.model_id=m.model_id AND newer.status='PUBLISHED' AND newer.version_no&gt;m.version_no)</if><if test='category != null and category != &quot;&quot;'> AND m.category_id=#{category}</if><if test='keyword != null and keyword != &quot;&quot;'> AND (m.name LIKE CONCAT('%',#{keyword},'%') OR m.tags LIKE CONCAT('%',#{keyword},'%'))</if> ORDER BY m.create_time DESC,m.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<CanvasLibraryModel> list(@Param("publicOnly") boolean publicOnly,@Param("category") String category,@Param("keyword") String keyword,@Param("limit") int limit,@Param("offset") int offset);
    @Insert("INSERT INTO aid_canvas_model_library(id,model_id,version_no,name,category_id,tags,status,storage_path,thumbnail_path,file_size,content_hash,metadata_json,revision) VALUES(#{id},#{modelId},#{versionNo},#{name},#{categoryId},#{tags},#{status},#{storagePath},#{thumbnailPath},#{fileSize},#{contentHash},#{metadataJson},1)") int insert(CanvasLibraryModel row);
    @Update("UPDATE aid_canvas_model_library SET name=#{name},category_id=#{categoryId},tags=#{tags},status=#{status},revision=revision+1 WHERE id=#{id} AND revision=#{revision}") int update(CanvasLibraryModel row);
    @Select("SELECT COUNT(*) FROM aid_canvas_model_usage WHERE version_id=#{id}") int references(String id);
    @Insert("INSERT INTO aid_canvas_model_usage(version_id,workspace_id,user_id,operation_id) VALUES(#{version},#{workspace},#{user},#{operation}) ON DUPLICATE KEY UPDATE version_id=version_id")
    int use(@Param("version") String version,@Param("workspace") Long workspace,@Param("user") Long user,@Param("operation") String operation);
}
