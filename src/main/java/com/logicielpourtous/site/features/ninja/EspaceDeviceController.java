package com.logicielpourtous.site.features.ninja;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.espace.EspaceContextService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/espace/devices")
public class EspaceDeviceController {

    private final NinjaOneConfigRepository ninjaConfigRepository;
    private final NinjaOneClient           ninjaOneClient;
    private final EspaceContextService     ctx;

    public EspaceDeviceController(NinjaOneConfigRepository ninjaConfigRepository,
                                  NinjaOneClient ninjaOneClient,
                                  EspaceContextService ctx) {
        this.ninjaConfigRepository = ninjaConfigRepository;
        this.ninjaOneClient        = ninjaOneClient;
        this.ctx                   = ctx;
    }

    @GetMapping
    public String devices(@AuthenticationPrincipal CustomUserDetails userDetails,
                          HttpSession session, Model model) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        model.addAttribute("customer", customer);
        if (customer.getNinjaOrganizationId() != null) {
            try {
                NinjaOneConfig cfg = ninjaConfigRepository.findById(1L).orElse(null);
                if (cfg != null && cfg.isEnabled()) {
                    List<NinjaDevice> devices = ninjaOneClient.getDevices(cfg, customer.getNinjaOrganizationId());
                    model.addAttribute("devices",      devices);
                    model.addAttribute("onlineCount",  devices.stream().filter(NinjaDevice::getOnline).count());
                    model.addAttribute("offlineCount", devices.stream().filter(d -> !d.getOnline()).count());
                }
            } catch (Exception e) {
                model.addAttribute("ninjaError", "Impossible de récupérer les appareils : " + e.getMessage());
            }
        }
        return "espace/devices";
    }
}
