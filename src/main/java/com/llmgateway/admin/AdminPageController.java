package com.llmgateway.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 控制台是前端构建产物。/admin 和 /admin/ 都进首页，hash 路由自己切视图。
 * 不映射 /admin/api，避免把接口转成页面。
 */
@Controller
public class AdminPageController {

    @GetMapping({"/admin", "/admin/"})
    public String page() {
        return "forward:/admin/index.html";
    }
}
