package com.logicielpourtous.site.features.user;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/users")
public class AdminUserController {

    @GetMapping({"", "/"})
    public String redirect() {
        return "redirect:/intra/users";
    }
}
