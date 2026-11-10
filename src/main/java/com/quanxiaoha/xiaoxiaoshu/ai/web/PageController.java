package com.quanxiaoha.xiaoxiaoshu.ai.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * @author: 犬小哈
 * @description: 前端页面入口
 **/
@Controller
public class PageController {

    @GetMapping({"/", "/ui"})
    public String index() {
        return "redirect:/ui/index.html";
    }

    @GetMapping("/legacy")
    public String legacy() {
        return "redirect:/legacy.html";
    }
}
