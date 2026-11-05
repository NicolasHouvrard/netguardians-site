package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/intra/account")
public class AccountApiController {

    private final UserRepository        userRepository;
    private final MailAccountRepository mailAccountRepository;
    private final PasswordEncoder       passwordEncoder;

    public AccountApiController(UserRepository userRepository,
                                MailAccountRepository mailAccountRepository,
                                PasswordEncoder passwordEncoder) {
        this.userRepository        = userRepository;
        this.mailAccountRepository = mailAccountRepository;
        this.passwordEncoder       = passwordEncoder;
    }

    /** Profil de l'utilisateur courant. */
    @GetMapping
    public Map<String, Object> profile(@AuthenticationPrincipal CustomUserDetails principal) {
        User user = userRepository.findById(principal.getUserId()).orElseThrow();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",       user.getId());
        m.put("email",    user.getEmail());
        m.put("fullName", user.getFullName() != null ? user.getFullName() : "");
        return m;
    }

    /** Mise à jour du nom complet. */
    @PutMapping("/profile")
    public Map<String, Object> updateProfile(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody Map<String, String> body) {
        User user = userRepository.findById(principal.getUserId()).orElseThrow();
        String fullName = body.getOrDefault("fullName", "").trim();
        user.setFullName(fullName);
        userRepository.save(user);
        return Map.of("fullName", fullName);
    }

    /** Changement de mot de passe – retourne toujours 200 avec success/error. */
    @PostMapping("/password")
    public Map<String, Object> changePassword(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody Map<String, String> body) {
        User user = userRepository.findById(principal.getUserId()).orElseThrow();
        String current  = body.getOrDefault("currentPassword",  "");
        String next     = body.getOrDefault("newPassword",      "");
        String confirm  = body.getOrDefault("confirmPassword",  "");

        if (!passwordEncoder.matches(current, user.getPassword()))
            return Map.of("success", false, "error", "Mot de passe actuel incorrect.");
        if (next.length() < 8)
            return Map.of("success", false, "error", "Le nouveau mot de passe doit faire au moins 8 caractères.");
        if (!next.equals(confirm))
            return Map.of("success", false, "error", "Les mots de passe ne correspondent pas.");

        user.setPassword(passwordEncoder.encode(next));
        user.setMustChangePassword(false);
        userRepository.save(user);
        return Map.of("success", true);
    }

    /** Agendas Google / Microsoft connectés pour l'utilisateur courant. */
    @GetMapping("/calendars")
    public List<Map<String, Object>> calendars(@AuthenticationPrincipal CustomUserDetails principal) {
        return mailAccountRepository.findAll().stream()
                .filter(a -> a.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2
                          || a.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2)
                .filter(a -> principal.getUserId().equals(a.getUserId()))
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",        a.getId());
                    m.put("name",      a.getName());
                    m.put("authType",  a.getAuthType().name());
                    m.put("username",  a.getUsername());
                    m.put("connected", a.getAccessToken() != null);
                    return m;
                })
                .collect(Collectors.toList());
    }
}
