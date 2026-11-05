package com.logicielpourtous.site.features.site;

import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.module.AdminModuleRepository;
import com.logicielpourtous.site.features.ninja.NinjaOneConfigRepository;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final CustomerRepository       customerRepository;
    private final UserRepository           userRepository;
    private final RequestRepository        requestRepository;
    private final NinjaOneConfigRepository ninjaConfigRepository;
    private final AdminModuleRepository    moduleRepository;

    public AdminController(CustomerRepository customerRepository,
                           UserRepository userRepository,
                           RequestRepository requestRepository,
                           NinjaOneConfigRepository ninjaConfigRepository,
                           AdminModuleRepository moduleRepository) {
        this.customerRepository    = customerRepository;
        this.userRepository        = userRepository;
        this.requestRepository     = requestRepository;
        this.ninjaConfigRepository = ninjaConfigRepository;
        this.moduleRepository      = moduleRepository;
    }

    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        return request.getRequestURI();
    }

    @GetMapping({"", "/"})
    public String root() {
        return "redirect:/admin/console";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "redirect:/admin/console";
    }

    @GetMapping("/console")
    public String console(Model model) {
        long totalCustomers = customerRepository.count();
        long totalUsers     = userRepository.count();
        long openTickets    = requestRepository.countByStatusIn(
                List.of(RequestStatus.NEW, RequestStatus.IN_PROGRESS));
        long totalModules   = moduleRepository.count();

        var ninjaCfg = ninjaConfigRepository.findById(1L).orElse(null);
        boolean ninjaEnabled = ninjaCfg != null && ninjaCfg.isEnabled();

        model.addAttribute("totalCustomers", totalCustomers);
        model.addAttribute("totalUsers",     totalUsers);
        model.addAttribute("openTickets",    openTickets);
        model.addAttribute("totalModules",   totalModules);
        model.addAttribute("ninjaEnabled",   ninjaEnabled);
        return "admin/console";
    }
}
