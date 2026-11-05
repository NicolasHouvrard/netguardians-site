package com.logicielpourtous.site.features.mail;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/intra/mail/accounts")
public class MailAccountController {

    private final MailAccountRepository repository;

    public MailAccountController(MailAccountRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("accounts",   repository.findAllByOrderByNameAsc());
        model.addAttribute("providers",  MailAccount.MailProvider.values());
        model.addAttribute("authTypes",  MailAccount.AuthType.values());
        return "intra/mail/accounts";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam MailAccount.MailProvider provider,
                         @RequestParam(defaultValue = "PASSWORD") MailAccount.AuthType authType,
                         @RequestParam String username,
                         @RequestParam(required = false) String password,
                         @RequestParam(required = false) String fromName,
                         @RequestParam(required = false) String imapHost,
                         @RequestParam(defaultValue = "993") int imapPort,
                         @RequestParam(defaultValue = "true")  boolean imapSsl,
                         @RequestParam(required = false) String smtpHost,
                         @RequestParam(defaultValue = "587") int smtpPort,
                         @RequestParam(defaultValue = "false") boolean smtpSsl,
                         @RequestParam(defaultValue = "true")  boolean smtpStarttls,
                         @RequestParam(required = false) String clientId,
                         @RequestParam(required = false) String clientSecret,
                         @RequestParam(required = false) String msTenantId,
                         RedirectAttributes ra) {
        MailAccount a = new MailAccount();
        applyFields(a, name, provider, authType, username, password, fromName,
                imapHost, imapPort, imapSsl, smtpHost, smtpPort, smtpSsl, smtpStarttls,
                clientId, clientSecret, msTenantId);
        repository.save(a);
        ra.addFlashAttribute("successMessage", "Compte mail ajouté.");
        return "redirect:/intra/mail/accounts";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam MailAccount.MailProvider provider,
                         @RequestParam(defaultValue = "PASSWORD") MailAccount.AuthType authType,
                         @RequestParam String username,
                         @RequestParam(required = false) String password,
                         @RequestParam(required = false) String fromName,
                         @RequestParam(required = false) String imapHost,
                         @RequestParam(defaultValue = "993") int imapPort,
                         @RequestParam(defaultValue = "true")  boolean imapSsl,
                         @RequestParam(required = false) String smtpHost,
                         @RequestParam(defaultValue = "587") int smtpPort,
                         @RequestParam(defaultValue = "false") boolean smtpSsl,
                         @RequestParam(defaultValue = "true")  boolean smtpStarttls,
                         @RequestParam(required = false) String clientId,
                         @RequestParam(required = false) String clientSecret,
                         @RequestParam(required = false) String msTenantId,
                         RedirectAttributes ra) {
        repository.findById(id).ifPresent(a -> {
            applyFields(a, name, provider, authType, username,
                    (password != null && !password.isBlank()) ? password : null,
                    fromName, imapHost, imapPort, imapSsl, smtpHost, smtpPort, smtpSsl, smtpStarttls,
                    clientId, clientSecret, msTenantId);
            repository.save(a);
        });
        ra.addFlashAttribute("successMessage", "Compte mail mis à jour.");
        return "redirect:/intra/mail/accounts";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id) {
        repository.findById(id).ifPresent(a -> {
            a.setActive(!a.isActive());
            repository.save(a);
        });
        return "redirect:/intra/mail/accounts";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        repository.deleteById(id);
        ra.addFlashAttribute("successMessage", "Compte supprimé.");
        return "redirect:/intra/mail/accounts";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyFields(MailAccount a, String name, MailAccount.MailProvider provider,
                              MailAccount.AuthType authType, String username, String password,
                              String fromName, String imapHost, int imapPort, boolean imapSsl,
                              String smtpHost, int smtpPort, boolean smtpSsl, boolean smtpStarttls,
                              String clientId, String clientSecret, String msTenantId) {
        a.setName(name);
        a.setProvider(provider);
        a.setAuthType(authType);
        a.setUsername(username);
        if (password != null) a.setEncryptedPassword(password);
        a.setFromName(fromName);
        a.setImapHost(imapHost);
        a.setImapPort(imapPort);
        a.setImapSsl(imapSsl);
        a.setSmtpHost(smtpHost);
        a.setSmtpPort(smtpPort);
        a.setSmtpSsl(smtpSsl);
        a.setSmtpStarttls(smtpStarttls);
        if (clientId != null && !clientId.isBlank())     a.setClientId(clientId);
        if (clientSecret != null && !clientSecret.isBlank()) a.setClientSecret(clientSecret);
        a.setMsTenantId(msTenantId != null && !msTenantId.isBlank() ? msTenantId : "common");
    }
}
