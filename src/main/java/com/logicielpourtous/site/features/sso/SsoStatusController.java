package com.logicielpourtous.site.features.sso;

import com.logicielpourtous.site.features.mail.MailAccount;
import com.logicielpourtous.site.features.mail.MailAccountRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/admin/sso-config")
public class SsoStatusController {

    private final MailAccountRepository          mailAccountRepository;
    private final ClientRegistrationRepository   clientRegistrationRepository;

    public SsoStatusController(MailAccountRepository mailAccountRepository,
                                ClientRegistrationRepository clientRegistrationRepository) {
        this.mailAccountRepository        = mailAccountRepository;
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @GetMapping
    public String index(Model model) {
        List<MailAccount> googleAccounts = mailAccountRepository.findAll().stream()
                .filter(a -> a.getAuthType() == MailAccount.AuthType.GOOGLE_OAUTH2)
                .filter(a -> a.getClientId() != null && !a.getClientId().isBlank())
                .toList();

        List<MailAccount> microsoftAccounts = mailAccountRepository.findAll().stream()
                .filter(a -> a.getAuthType() == MailAccount.AuthType.MICROSOFT_OAUTH2)
                .filter(a -> a.getClientId() != null && !a.getClientId().isBlank())
                .toList();

        boolean googleActive    = clientRegistrationRepository.findByRegistrationId("google")    != null;
        boolean microsoftActive = clientRegistrationRepository.findByRegistrationId("microsoft") != null;

        model.addAttribute("googleAccounts",    googleAccounts);
        model.addAttribute("microsoftAccounts", microsoftAccounts);
        model.addAttribute("googleActive",      googleActive);
        model.addAttribute("microsoftActive",   microsoftActive);

        return "admin/sso-config";
    }
}
