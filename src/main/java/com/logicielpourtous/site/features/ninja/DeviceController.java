package com.logicielpourtous.site.features.ninja;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/intra/devices")
public class DeviceController {

    private final CustomerRepository        customerRepository;
    private final NinjaOneConfigRepository  ninjaConfigRepository;
    private final NinjaOneClient            ninjaOneClient;

    public DeviceController(CustomerRepository customerRepository,
                            NinjaOneConfigRepository ninjaConfigRepository,
                            NinjaOneClient ninjaOneClient) {
        this.customerRepository    = customerRepository;
        this.ninjaConfigRepository = ninjaConfigRepository;
        this.ninjaOneClient        = ninjaOneClient;
    }

    @GetMapping
    public String list(Model model) {
        List<ClientDevices> clientDevices = new ArrayList<>();
        NinjaOneConfig cfg = ninjaConfigRepository.findById(1L).orElse(null);

        if (cfg != null && cfg.isEnabled()) {
            for (Customer customer : customerRepository.findByNinjaOrganizationIdIsNotNull()) {
                try {
                    List<NinjaDevice> devices = ninjaOneClient.getDevices(cfg, customer.getNinjaOrganizationId());
                    long online  = devices.stream().filter(NinjaDevice::getOnline).count();
                    long offline = devices.stream().filter(d -> !d.getOnline()).count();
                    clientDevices.add(new ClientDevices(customer, devices, online, offline));
                } catch (Exception e) {
                    clientDevices.add(new ClientDevices(customer, List.of(), 0, 0));
                }
            }
        }

        long totalOnline  = clientDevices.stream().mapToLong(ClientDevices::onlineCount).sum();
        long totalOffline = clientDevices.stream().mapToLong(ClientDevices::offlineCount).sum();

        model.addAttribute("clientDevices", clientDevices);
        model.addAttribute("ninjaEnabled",  cfg != null && cfg.isEnabled());
        model.addAttribute("totalOnline",   totalOnline);
        model.addAttribute("totalOffline",  totalOffline);
        return "intra/devices/list";
    }

    public record ClientDevices(Customer customer, List<NinjaDevice> devices,
                                long onlineCount, long offlineCount) {}
}
