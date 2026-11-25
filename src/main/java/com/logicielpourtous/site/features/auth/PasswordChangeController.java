package com.logicielpourtous.site.features.auth;

import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/change-password")
public class PasswordChangeController {

    private final UserRepository  userRepository;
    private final PasswordEncoder passwordEncoder;

    public PasswordChangeController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository  = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public String showForm() {
        return "change-password";
    }

    @PostMapping
    public String changePassword(@AuthenticationPrincipal CustomUserDetails userDetails,
                                 @RequestParam String password,
                                 @RequestParam String confirmPassword,
                                 HttpSession session,
                                 Model model,
                                 RedirectAttributes ra) {
        if (!password.equals(confirmPassword)) {
            model.addAttribute("errorMessage", "Les mots de passe ne correspondent pas.");
            return "change-password";
        }
        if (password.length() < 8) {
            model.addAttribute("errorMessage", "Le mot de passe doit contenir au moins 8 caractères.");
            return "change-password";
        }

        User user = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        user.setPassword(passwordEncoder.encode(password));
        user.setMustChangePassword(false);
        userRepository.save(user);
        session.removeAttribute("MUST_CHANGE_PASSWORD");

        ra.addFlashAttribute("successMessage", "Mot de passe modifié avec succès.");
        return "redirect:/accueil";
    }
}
