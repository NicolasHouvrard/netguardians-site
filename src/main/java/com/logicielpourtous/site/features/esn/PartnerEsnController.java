package com.logicielpourtous.site.features.esn;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/intra/esns")
public class PartnerEsnController {

    private final PartnerEsnRepository esnRepository;

    public PartnerEsnController(PartnerEsnRepository esnRepository) {
        this.esnRepository = esnRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("esns", esnRepository.findAll());
        return "intra/esns/list";
    }

    @PostMapping
    public String create(@RequestParam String companyName,
                         @RequestParam(required = false) String contactName,
                         @RequestParam(required = false) String email,
                         @RequestParam(required = false) String phone,
                         @RequestParam(required = false) String address,
                         @RequestParam(required = false) String city,
                         @RequestParam(required = false) String zipCode,
                         @RequestParam(required = false) String siret,
                         @RequestParam(required = false) String vatNumber,
                         @RequestParam(required = false) BigDecimal defaultHourlyRate,
                         @RequestParam(required = false) String notes,
                         RedirectAttributes ra) {
        PartnerEsn esn = new PartnerEsn();
        esn.setCompanyName(companyName);
        esn.setContactName(contactName);
        esn.setEmail(email);
        esn.setPhone(phone);
        esn.setAddress(address);
        esn.setCity(city);
        esn.setZipCode(zipCode);
        esn.setSiret(siret);
        esn.setVatNumber(vatNumber);
        esn.setDefaultHourlyRate(defaultHourlyRate);
        esn.setNotes(notes);
        esnRepository.save(esn);
        ra.addFlashAttribute("successMessage", "ESN partenaire " + companyName + " créée.");
        return "redirect:/intra/esns";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String companyName,
                         @RequestParam(required = false) String contactName,
                         @RequestParam(required = false) String email,
                         @RequestParam(required = false) String phone,
                         @RequestParam(required = false) String address,
                         @RequestParam(required = false) String city,
                         @RequestParam(required = false) String zipCode,
                         @RequestParam(required = false) String siret,
                         @RequestParam(required = false) String vatNumber,
                         @RequestParam(required = false) BigDecimal defaultHourlyRate,
                         @RequestParam(required = false) String notes,
                         RedirectAttributes ra) {
        esnRepository.findById(id).ifPresent(esn -> {
            esn.setCompanyName(companyName);
            esn.setContactName(contactName);
            esn.setEmail(email);
            esn.setPhone(phone);
            esn.setAddress(address);
            esn.setCity(city);
            esn.setZipCode(zipCode);
            esn.setSiret(siret);
            esn.setVatNumber(vatNumber);
            esn.setDefaultHourlyRate(defaultHourlyRate);
            esn.setNotes(notes);
            esnRepository.save(esn);
        });
        ra.addFlashAttribute("successMessage", "ESN mise à jour.");
        return "redirect:/intra/esns";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        esnRepository.findById(id).ifPresent(esn -> {
            esn.setActive(!esn.isActive());
            esnRepository.save(esn);
        });
        ra.addFlashAttribute("successMessage", "Statut modifié.");
        return "redirect:/intra/esns";
    }
}
