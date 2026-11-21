package com.aid.director.controller;

import com.aid.director.DirectorSessionService;
import com.aid.director.DirectorException;
import com.aid.director.dto.DirectorRequests;
import com.aid.common.annotation.Anonymous;
import com.aid.common.aid.crypto.annotation.CryptoIgnore;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.domain.model.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅凭限域配对凭证进入的手机接口。 */
@Anonymous
@RestController
@RequestMapping("/api/user/studio/director/virtual-camera")
@RequiredArgsConstructor
public class DirectorGuestController {
    private final DirectorSessionService sessions;

    @PostMapping("/session/join")
    public AjaxResult join(@Valid @RequestBody DirectorRequests.Join request, HttpServletRequest http) {
        return AjaxResult.success(sessions.join(request, DirectorClientAddress.resolve(http.getRemoteAddr(), http.getHeader("X-Real-IP"))));
    }
    @PostMapping("/session/state")
    public AjaxResult state(@Valid @RequestBody DirectorRequests.Session request, HttpServletRequest http) {
        String grant = http.getHeader("X-Virtual-Camera-Grant");
        return AjaxResult.success(sessions.state(request.getSessionId(), grant == null ? user() : null, grant));
    }
    @PostMapping("/session/ticket")
    public AjaxResult ticket(@Valid @RequestBody DirectorRequests.Session request, HttpServletRequest http) {
        String grant = http.getHeader("X-Virtual-Camera-Grant");
        return AjaxResult.success(sessions.ticket(request.getSessionId(), grant == null ? user() : null, grant));
    }

    // 非Upgrade请求返回明确状态；精确路径同时供匿名路径发现器登记，真正握手由WS handler处理。
    @CryptoIgnore
    @GetMapping(value = "/ws", headers = "Upgrade!=websocket")
    public ResponseEntity<Void> upgradeRequired() { return ResponseEntity.status(426).build(); }

    private Long user() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser login))
            throw new DirectorException("AUTH_REQUIRED", 401, "请先登录");
        return login.getUserId();
    }
}
