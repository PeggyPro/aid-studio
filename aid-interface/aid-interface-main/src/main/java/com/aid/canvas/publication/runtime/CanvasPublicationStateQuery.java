package com.aid.canvas.publication.runtime;

import com.aid.canvas.mapper.CanvasPublicationRuntimeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 仅组合已经过项目权限查询的结果；列表一次批量读取，不依赖发布编排服务。 */
@Service
@RequiredArgsConstructor
public class CanvasPublicationStateQuery {
    private final CanvasPublicationRuntimeMapper mapper;
    public void enrich(List<Map<String,Object>> projects) {
        if(projects.isEmpty()) return;
        List<Long> ids=projects.stream().map(p->Long.valueOf(p.get("projectId").toString())).distinct().toList();
        Map<String,Map<String,Object>> states=new HashMap<>();
        for(Map<String,Object> row:mapper.projectStates(ids)) states.put(row.get("projectId").toString(),row);
        for(Map<String,Object> project:projects) {
            Map<String,Object> row=states.get(project.get("projectId").toString());
            if(row==null) continue;
            boolean approved="APPROVED".equals(row.get("publishedReviewStatus"));
            boolean visible=approved&&truth(row.get("visible"))&&!truth(row.get("blocked"));
            boolean dirty=!approved||!Objects.equals(string(row.get("draftRevision")),string(row.get("publishedRevision")))
                    ||!Objects.equals(string(row.get("sceneVersion")),string(row.get("publishedSceneVersion")));
            Map<String,Object> state=new LinkedHashMap<>();
            state.put("stateVersion",string(row.get("stateVersion"))); state.put("sceneVersion",string(row.get("sceneVersion")));
            state.put("draftRevision",string(row.get("draftRevision")));state.put("publishedRevision",string(row.get("publishedRevision")));
            state.put("visibility",truth(row.get("blocked"))?"ADMIN_OFFLINE":visible?"PUBLIC":"PRIVATE");
            state.put("isPublic",visible);state.put("allowCopy",visible&&truth(row.get("copyEnabled")));
            state.put("hasApprovedVersion",approved);state.put("isCurrentDraftApproved",!dirty);state.put("hasUnpublishedChanges",dirty);
            state.put("publishedId",string(row.get("publishedId")));state.put("pendingId",string(row.get("pendingId")));
            state.put("reviewStatus",row.get("pendingReviewStatus")!=null?row.get("pendingReviewStatus"):
                    row.get("latestReviewStatus")!=null?row.get("latestReviewStatus"):"DRAFT");
            state.put("isDerivative",row.get("sourcePublicationId")!=null);
            List<String> actions=new ArrayList<>();
            if("OWNER".equals(project.get("roleCode"))) {
                if(visible) actions.add("WITHDRAW");
                if(visible&&truth(row.get("copyEnabled"))) actions.add("DISABLE_COPY");
                if(row.get("pendingId")!=null) actions.add("CANCEL_REVIEW");
            }
            state.put("availableActions",actions);project.put("publicationState",state);
        }
    }
    private static String string(Object value) { return value==null?null:value.toString(); }
    private static boolean truth(Object value) { return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()!=0; }
}
