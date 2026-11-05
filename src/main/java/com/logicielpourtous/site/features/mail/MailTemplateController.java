package com.logicielpourtous.site.features.mail;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/mail/templates")
public class MailTemplateController {

    private final MailTemplateRepository templateRepository;
    private final MailTemplateService    templateService;

    public MailTemplateController(MailTemplateRepository templateRepository,
                                  MailTemplateService templateService) {
        this.templateRepository = templateRepository;
        this.templateService    = templateService;
    }

    @GetMapping
    public String list(Model model) {
        // Garantit que tous les types ont une entrée (crée les manquantes depuis les défauts)
        Map<MailTemplateType, MailTemplate> saved = templateRepository.findAllByOrderByTypeAsc()
                .stream().collect(Collectors.toMap(MailTemplate::getType, t -> t));

        List<MailTemplate> all = Arrays.stream(MailTemplateType.values())
                .map(type -> saved.containsKey(type)
                        ? saved.get(type)
                        : templateService.getOrCreateDefault(type))
                .collect(java.util.stream.Collectors.toList());

        model.addAttribute("templates", all);
        return "intra/mail/templates/list";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        MailTemplate tpl = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template introuvable"));
        model.addAttribute("tpl", tpl);
        return "intra/mail/templates/edit";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String subject,
                         @RequestParam String htmlBody,
                         RedirectAttributes ra) {
        templateRepository.findById(id).ifPresent(tpl -> {
            tpl.setSubject(subject);
            tpl.setHtmlBody(htmlBody);
            templateRepository.save(tpl);
        });
        ra.addFlashAttribute("successMessage", "Template mis à jour.");
        return "redirect:/intra/mail/templates";
    }

    @PostMapping("/{id}/reset")
    public String reset(@PathVariable Long id, RedirectAttributes ra) {
        templateRepository.findById(id).ifPresent(tpl -> {
            MailTemplate def = templateService.getOrCreateDefault(tpl.getType());
            tpl.setSubject(def.getSubject());
            tpl.setHtmlBody(def.getHtmlBody());
            templateRepository.save(tpl);
        });
        ra.addFlashAttribute("successMessage", "Template réinitialisé aux valeurs par défaut.");
        return "redirect:/intra/mail/templates";
    }
}
