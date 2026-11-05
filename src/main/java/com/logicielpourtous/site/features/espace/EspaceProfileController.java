package com.logicielpourtous.site.features.espace;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/espace/profile")
public class EspaceProfileController {

    private final UserRepository       userRepository;
    private final PasswordEncoder      passwordEncoder;
    private final EspaceContextService ctx;

    public EspaceProfileController(UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   EspaceContextService ctx) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.ctx             = ctx;
    }

    @GetMapping
    public String profile(@AuthenticationPrincipal CustomUserDetails userDetails,
                          HttpSession session, Model model) {
        User user = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        model.addAttribute("editUser", user);
        try {
            model.addAttribute("customer", ctx.loadCustomer(userDetails, session));
        } catch (PortalNotLinkedException ignored) {}
        return "espace/profile";
    }

    @PostMapping
    public String save(@AuthenticationPrincipal CustomUserDetails userDetails,
                       @RequestParam String fullName,
                       @RequestParam(required = false) String password,
                       RedirectAttributes ra) {
        User user = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        user.setFullName(fullName);
        if (password != null && !password.isBlank()) {
            user.setPassword(passwordEncoder.encode(password));
        }
        userRepository.save(user);
        ra.addFlashAttribute("successMessage", "Profil mis à jour.");
        return "redirect:/espace/profile";
    }
}
