package com.logicielpourtous.site.features.booking;

import com.logicielpourtous.site.features.appointment.Appointment;
import com.logicielpourtous.site.features.team.TeamRepository;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/booking")
public class IntraBookingController {

    private final BookingConfigRepository bookingConfigRepository;
    private final TeamRepository teamRepository;

    public IntraBookingController(BookingConfigRepository bookingConfigRepository,
                                  TeamRepository teamRepository) {
        this.bookingConfigRepository = bookingConfigRepository;
        this.teamRepository = teamRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String list(Model model) {
        model.addAttribute("configs", bookingConfigRepository.findAllByOrderByTitleAsc());
        return "intra/booking/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        BookingConfig config = new BookingConfig();
        model.addAttribute("config", config);
        model.addAttribute("teams", teamRepository.findAll());
        model.addAttribute("appointmentTypes", Appointment.AppointmentType.values());
        model.addAttribute("daysOfWeek", DayOfWeek.values());
        return "intra/booking/form";
    }

    @PostMapping
    @Transactional
    public String save(@ModelAttribute BookingConfig config,
                       @RequestParam(value = "openDays", required = false) List<String> openDays,
                       @RequestParam(value = "teamId", required = false) Long teamId,
                       RedirectAttributes ra) {
        config.setOpenDays(parseDays(openDays));
        if (teamId != null) {
            teamRepository.findById(teamId).ifPresent(config::setTeam);
        }
        bookingConfigRepository.save(config);
        ra.addFlashAttribute("successMessage", "Configuration créée avec succès.");
        return "redirect:/intra/booking";
    }

    @GetMapping("/{id}/edit")
    @Transactional(readOnly = true)
    public String editForm(@PathVariable Long id, Model model) {
        BookingConfig config = bookingConfigRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config introuvable"));
        model.addAttribute("config", config);
        model.addAttribute("teams", teamRepository.findAll());
        model.addAttribute("appointmentTypes", Appointment.AppointmentType.values());
        model.addAttribute("daysOfWeek", DayOfWeek.values());
        return "intra/booking/form";
    }

    @PostMapping("/{id}/update")
    @Transactional
    public String update(@PathVariable Long id,
                         @ModelAttribute BookingConfig form,
                         @RequestParam(value = "openDays", required = false) List<String> openDays,
                         @RequestParam(value = "teamId", required = false) Long teamId,
                         RedirectAttributes ra) {
        BookingConfig config = bookingConfigRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config introuvable"));
        config.setSlug(form.getSlug());
        config.setTitle(form.getTitle());
        config.setDescription(form.getDescription());
        config.setAppointmentType(form.getAppointmentType());
        config.setDurationMinutes(form.getDurationMinutes());
        config.setBufferMinutes(form.getBufferMinutes());
        config.setOpenDays(parseDays(openDays));
        config.setOpenFrom(form.getOpenFrom());
        config.setOpenTo(form.getOpenTo());
        config.setAdvanceDaysMax(form.getAdvanceDaysMax());
        config.setWithMeet(form.isWithMeet());
        config.setActive(form.isActive());
        if (teamId != null) {
            teamRepository.findById(teamId).ifPresent(config::setTeam);
        } else {
            config.setTeam(null);
        }
        bookingConfigRepository.save(config);
        ra.addFlashAttribute("successMessage", "Configuration mise à jour.");
        return "redirect:/intra/booking";
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        bookingConfigRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "Configuration supprimée.");
        return "redirect:/intra/booking";
    }

    @PostMapping("/{id}/toggle")
    @Transactional
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        bookingConfigRepository.findById(id).ifPresent(c -> {
            c.setActive(!c.isActive());
            bookingConfigRepository.save(c);
        });
        ra.addFlashAttribute("successMessage", "Statut modifié.");
        return "redirect:/intra/booking";
    }

    private Set<DayOfWeek> parseDays(List<String> days) {
        if (days == null || days.isEmpty()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }
        return days.stream()
                .map(DayOfWeek::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }
}
