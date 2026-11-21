package com.aid.businessscene.controller;

import com.aid.businessscene.BusinessEntrypointService;
import com.aid.businessscene.BusinessSceneQuoteService;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/user/business-scene")
public class BusinessSceneController {
    private final BusinessEntrypointService scenes;private final BusinessSceneQuoteService quotes;
    public BusinessSceneController(BusinessEntrypointService scenes,BusinessSceneQuoteService quotes){this.scenes=scenes;this.quotes=quotes;}
    @GetMapping("/{scene}/catalog")
    public AjaxResult catalog(@PathVariable("scene")String scene){SecurityUtils.getUserId();return AjaxResult.success(scenes.read(scene));}
    @PostMapping("/{scene}/quote")
    public AjaxResult quote(@PathVariable("scene")String scene,@RequestBody BusinessSceneQuoteService.Request input){return AjaxResult.success(quotes.quote(scene,input,SecurityUtils.getUserId()));}
}
