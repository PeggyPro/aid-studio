package com.aid.director.controller;

import com.aid.director.DirectorSceneService;
import com.aid.director.DirectorSessionService;
import com.aid.director.DirectorRecordingService;
import com.aid.director.dto.DirectorRequests;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 导演机位与录制持久化接口。 */
@RestController
@RequestMapping("/api/user/studio/director")
@RequiredArgsConstructor
public class DirectorController {
    private final DirectorSceneService scenes;
    private final DirectorSessionService sessions;
    private final DirectorRecordingService recordings;

    @PostMapping("/scene/save")
    public AjaxResult save(@Valid @RequestBody DirectorRequests.SceneSave request) { return AjaxResult.success(scenes.save(request, user())); }
    @PostMapping("/scene/detail")
    public AjaxResult detail(@Valid @RequestBody DirectorRequests.SceneKey request) { return AjaxResult.success(scenes.detail(request, user())); }
    @PostMapping("/scene/list")
    public AjaxResult list(@Valid @RequestBody DirectorRequests.SceneList request) { return AjaxResult.success(scenes.list(request, user())); }
    @PostMapping("/scene/delete")
    public AjaxResult delete(@Valid @RequestBody DirectorRequests.SceneDelete request) { return AjaxResult.success(scenes.delete(request, user())); }
    @PostMapping("/virtual-camera/session/create")
    public AjaxResult create(@Valid @RequestBody DirectorRequests.Create request) { return AjaxResult.success(sessions.create(request, user())); }
    @PostMapping("/virtual-camera/session/action")
    public AjaxResult action(@Valid @RequestBody DirectorRequests.Action request) {
        var result = sessions.action(request, user()); sessions.announce(request.getSessionId()); return AjaxResult.success(result);
    }
    @PostMapping("/virtual-camera/session/negotiate")
    public AjaxResult negotiate(@Valid @RequestBody DirectorRequests.Negotiate request) {
        var result = sessions.negotiate(request, user()); sessions.announce(request.getSessionId()); return AjaxResult.success(result);
    }
    @PostMapping("/virtual-camera/session/lease")
    public AjaxResult lease(@Valid @RequestBody DirectorRequests.Lease request) { return AjaxResult.success(sessions.lease(request, user())); }
    @PostMapping("/virtual-camera/session/close")
    public AjaxResult close(@Valid @RequestBody DirectorRequests.Session request) { return AjaxResult.success(sessions.close(request.getSessionId(), user())); }
    @PostMapping("/virtual-camera/recording/control")
    public AjaxResult control(@Valid @RequestBody DirectorRequests.RecordingControl request) { return AjaxResult.success(recordings.control(request, user())); }
    @PostMapping("/virtual-camera/recording/confirm")
    public AjaxResult confirm(@Valid @RequestBody DirectorRequests.Confirm request) { return AjaxResult.success(recordings.confirm(request, user())); }
    @PostMapping("/virtual-camera/recording/chunk")
    public AjaxResult chunk(@Valid @RequestBody DirectorRequests.Chunk request) { return AjaxResult.success(recordings.append(request, user())); }
    @PostMapping("/virtual-camera/recording/state")
    public AjaxResult recordingState(@Valid @RequestBody DirectorRequests.TakeKey request) { return AjaxResult.success(recordings.state(request.getTakeId(), user())); }
    @PostMapping("/virtual-camera/recording/finalize")
    public AjaxResult finalizeTake(@Valid @RequestBody DirectorRequests.Finalize request) { return AjaxResult.success(recordings.finalizeTake(request, user())); }
    @PostMapping("/virtual-camera/recording/apply")
    public AjaxResult apply(@Valid @RequestBody DirectorRequests.Apply request) { return AjaxResult.success(recordings.apply(request, user())); }
    @PostMapping("/virtual-camera/recording/recover")
    public AjaxResult recover(@Valid @RequestBody DirectorRequests.Recover request) { return AjaxResult.success(recordings.recover(request, user())); }
    @PostMapping("/virtual-camera/recording/discard")
    public AjaxResult discard(@Valid @RequestBody DirectorRequests.TakeKey request) { return AjaxResult.success(recordings.discard(request, user())); }
    @PostMapping("/virtual-camera/recording/list")
    public AjaxResult recordingList(@Valid @RequestBody DirectorRequests.TakeList request) { return AjaxResult.success(recordings.list(request, user())); }
    @PostMapping("/virtual-camera/recording/read")
    public AjaxResult read(@Valid @RequestBody DirectorRequests.Read request) { return AjaxResult.success(recordings.read(request, user())); }

    private Long user() { return SecurityUtils.getUserId(); }
}
