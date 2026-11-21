package com.aid.compute.mapper;

import com.aid.compute.domain.AidComputeNode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

public interface AidComputeNodeMapper extends BaseMapper<AidComputeNode> {
    @Select("SELECT node_key,revision,config_json AS draft_config_json,worker_id AS draft_worker_id,credential_ciphertext AS draft_credential_ciphertext FROM aid_compute_node_revision WHERE node_key=#{key} AND revision=#{revision}")
    AidComputeNode readRevision(@Param("key") String key,@Param("revision") long revision);
    @Insert("INSERT INTO aid_compute_node(node_key,name,pool_code,draft_config_json,revision,state,draft_worker_id,draft_credential_ciphertext,verification_json,verified_at,create_by,update_by,create_time,update_time) VALUES(#{nodeKey},#{name},#{poolCode},#{draftConfigJson},#{revision},#{state},#{draftWorkerId},#{draftCredentialCiphertext},#{verificationJson},#{verifiedAt},#{createBy},#{updateBy},#{createTime},#{updateTime}) ON DUPLICATE KEY UPDATE node_key=node_key")
    int insertIfAbsent(AidComputeNode row);
    @Select("SELECT node_key,name,pool_code,draft_config_json,revision,active_config_json,active_revision,state,health_status,health_generation,health_json,health_checked_at,draft_worker_id,draft_credential_ciphertext,verification_json,verified_at,create_by,update_by,create_time,update_time FROM aid_compute_node WHERE node_key=#{key} FOR UPDATE")
    AidComputeNode lock(@Param("key") String key);
    @Insert("INSERT INTO aid_compute_node_revision(node_key,revision,config_json,worker_id,credential_ciphertext,verification_json,verified_at,create_by,create_time) VALUES(#{nodeKey},#{revision},#{draftConfigJson},#{draftWorkerId},#{draftCredentialCiphertext},#{verificationJson},#{verifiedAt},#{updateBy},#{updateTime}) ON DUPLICATE KEY UPDATE node_key=node_key")
    int archiveRevision(AidComputeNode row);
}
