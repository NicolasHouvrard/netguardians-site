package com.logicielpourtous.site.features.vatrate;

import com.logicielpourtous.site.features.customers.Customer;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/intra/vat-rates")
public class VatRateController {

    private final VatRateRepository vatRateRepository;

    public VatRateController(VatRateRepository vatRateRepository) {
        this.vatRateRepository = vatRateRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("vatRates", vatRateRepository.findByActiveTrueOrderByZoneAscRateDesc());
        model.addAttribute("zones",    Customer.FiscalZone.values());
        return "intra/vat-rates/list";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam BigDecimal rate,
                         @RequestParam Customer.FiscalZone zone,
                         @RequestParam(defaultValue = "false") boolean isDefault,
                         RedirectAttributes ra) {
        if (isDefault) clearDefault(zone);
        VatRate vr = new VatRate();
        vr.setName(name);
        vr.setRate(rate);
        vr.setZone(zone);
        vr.setDefault(isDefault);
        vatRateRepository.save(vr);
        ra.addFlashAttribute("successMessage", "Taux « " + vr.getLabel() + " » créé.");
        return "redirect:/intra/vat-rates";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam BigDecimal rate,
                         @RequestParam Customer.FiscalZone zone,
                         @RequestParam(defaultValue = "false") boolean isDefault,
                         RedirectAttributes ra) {
        VatRate vr = vatRateRepository.findById(id).orElseThrow();
        if (isDefault) clearDefault(zone);
        vr.setName(name);
        vr.setRate(rate);
        vr.setZone(zone);
        vr.setDefault(isDefault);
        vatRateRepository.save(vr);
        ra.addFlashAttribute("successMessage", "Taux mis à jour.");
        return "redirect:/intra/vat-rates";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        vatRateRepository.findById(id).ifPresent(vr -> {
            vr.setActive(false);
            vatRateRepository.save(vr);
        });
        ra.addFlashAttribute("successMessage", "Taux désactivé.");
        return "redirect:/intra/vat-rates";
    }

    @GetMapping("/by-zone")
    @ResponseBody
    public List<Map<String, Object>> byZone(@RequestParam Customer.FiscalZone zone) {
        return vatRateRepository.findByZoneAndActiveTrueOrderByRateDesc(zone)
                .stream()
                .map(vr -> Map.<String, Object>of(
                        "id",        vr.getId(),
                        "label",     vr.getLabel(),
                        "rate",      vr.getRate(),
                        "isDefault", vr.isDefault()))
                .toList();
    }

    private void clearDefault(Customer.FiscalZone zone) {
        vatRateRepository.findByZoneAndActiveTrueOrderByRateDesc(zone)
                .forEach(vr -> { vr.setDefault(false); vatRateRepository.save(vr); });
    }
}
