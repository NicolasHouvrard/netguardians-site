package com.logicielpourtous.site.features.user;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/mon-compte")
public class AccountController {

    private final UserRepository      userRepository;
    private final MailAccountRepository mailAccountRepository;
    private final PasswordEncoder     passwordEncoder;

    public AccountController(UserRepository userRepository,
                             MailAccountRepository mailAccountRepository,
                             PasswordEncoder passwordEncoder) {
        this.userRepository       = userRepository;
        this.mailAccountRepository = mailAccountRepository;
        this.passwordEncoder      = passwordEncoder;
    }

    @GetMapping
    public String show(@AuthenticationPrincipal CustomUserDetails principal,
                       @RequestParam(defaultValue = "profil") String tab,
                       Model model) {
        User user = userRepository.findById(principal.getUserId()).orElseThrow();
        model.addAttribute("user", user);
        model.addAttribute("tab", tab);

        // Comptes agenda personnels de l'utilisateur (Google / Microsoft avec token)
        List<MailAccount> calendarAccounts = mailAccountRepository.findAll().stream()
                .filter(a -> a.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2
                          || a.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2)
                .filter(a -> principal.getUserId().equals(a.getUserId()))
                .toList();
        model.addAttribute("calendarAccounts", calendarAccounts);

        return "mon-compte";
    }

    @PostMapping("/profile")
    public String updateProfile(@AuthenticationPrincipal CustomUserDetails principal,
                                @RequestParam String fullName,
                                RedirectAttributes ra) {
        User user = userRepository.findById(principal.getUserId()).orElseThrow();
        user.setFullName(fullName != null ? fullName.trim() : "");
        userRepository.save(user);
        ra.addFlashAttribute("successMessage", "Profil mis à jour.");
        ra.addFlashAttribute("tab", "profil");
        return "redirect:/mon-compte";
    }

    @PostMapping("/password")
    public String changePassword(@AuthenticationPrincipal CustomUserDetails principal,
                                 @RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 RedirectAttributes ra) {
        User user = userRepository.findById(principal.getUserId()).orElseThrow();

        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            ra.addFlashAttribute("errorMessage", "Mot de passe actuel incorrect.");
            ra.addFlashAttribute("tab", "securite");
            return "redirect:/mon-compte";
        }
        if (newPassword == null || newPassword.length() < 8) {
            ra.addFlashAttribute("errorMessage", "Le nouveau mot de passe doit faire au moins 8 caractères.");
            ra.addFlashAttribute("tab", "securite");
            return "redirect:/mon-compte";
        }
        if (!newPassword.equals(confirmPassword)) {
            ra.addFlashAttribute("errorMessage", "Les mots de passe ne correspondent pas.");
            ra.addFlashAttribute("tab", "securite");
            return "redirect:/mon-compte";
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
        ra.addFlashAttribute("successMessage", "Mot de passe modifié avec succès.");
        return "redirect:/mon-compte";
    }
}
