package com.aid.previz.service;
import com.aid.common.exception.ServiceException;
import com.aid.previz.mapper.*;
import com.aid.previz.domain.PrevizProjectMember;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PrevizMembershipService {
 private final PrevizMembershipMapper mapper;
 private final PrevizProjectMemberMapper members;
 private final PrevizProjectService access;
 public Map<String,Object> page(Long actor,String before,int limit){
  if(actor==null||actor<1)throw new ServiceException("请先登录",401);
  if(limit<1||limit>50||before!=null&&!before.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("项目分页无效");
  var rows=mapper.page(actor,before,limit);Map<String,Object> result=new LinkedHashMap<>();result.put("items",rows);result.put("nextCursor",rows.size()==limit?rows.get(rows.size()-1).getId():null);return result;
 }
 public Map<String,Object> list(Long actor,String project){
  var metadata=access.detail(actor,project);if(!Objects.equals(actor,metadata.getOwnerUserId()))throw new ServiceException("只有项目所有者可管理成员",403);
  return Map.of("ownerUserId",String.valueOf(actor),"revision",String.valueOf(metadata.getRevision()),"members",mapper.members(project));
 }
 @Transactional
 public Map<String,Object> change(Long actor,String project,Long user,String role,long expected){
  access.requireRead(actor,project);var head=mapper.lock(project);
  if(head==null||!String.valueOf(actor).equals(head.getOwnerUserId()))throw new ServiceException("只有项目所有者可管理成员",403);
  if(user==null||user<1||Objects.equals(user,actor)||role==null||!Set.of("EDITOR","VIEWER","REMOVE").contains(role))throw new IllegalArgumentException("成员及角色无效");
  if(!String.valueOf(expected).equals(head.getRevision()))throw new ServiceException("成员设置已变化，请刷新后操作",409);
  var member=members.selectOne(Wrappers.<PrevizProjectMember>lambdaQuery().eq(PrevizProjectMember::getProjectId,project).eq(PrevizProjectMember::getUserId,user));
  if("REMOVE".equals(role)&&(member==null||!"0".equals(member.getDelFlag()))||member!=null&&"0".equals(member.getDelFlag())&&role.equals(member.getRoleCode()))return list(actor,project);
  if(!"REMOVE".equals(role)&&mapper.userExists(user)!=1)throw new ServiceException("目标账号不可用",400);
  if(!"REMOVE".equals(role)&&(member==null||!"0".equals(member.getDelFlag()))&&mapper.members(project).size()>=500)throw new ServiceException("项目成员数量达到限制",409);
  var now=LocalDateTime.now();if(member==null){member=new PrevizProjectMember();member.setProjectId(project);member.setUserId(user);member.setCreateBy(actor);member.setCreateTime(now);}
  member.setRoleCode("REMOVE".equals(role)?member.getRoleCode():role);member.setDelFlag("REMOVE".equals(role)?"2":"0");member.setUpdateBy(actor);member.setUpdateTime(now);
  if(member.getId()==null)members.insert(member);else members.updateById(member);
  if(mapper.advance(project,actor,expected)!=1)throw new ServiceException("成员设置冲突",409);
  return list(actor,project);
 }
}
