package com.logicielpourtous.site.features.team;

import com.logicielpourtous.site.features.request.RequestType;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Controller
@RequestMapping("/intra/teams")
public class TeamController {

    private final TeamRepository teamRepository;
    private final UserRepository userRepository;

    public TeamController(TeamRepository teamRepository, UserRepository userRepository) {
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String list(Model model) {
        List<Team> teams = teamRepository.findAll();
        teams.forEach(t -> { t.getMembers().size(); t.getRequestTypes().size(); });
        model.addAttribute("teams", teams);
        return "intra/teams/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("team", new Team());
        model.addAttribute("internalUsers", userRepository.findInternalUsers());
        model.addAttribute("requestTypes", RequestType.values());
        return "intra/teams/form";
    }

    @GetMapping("/{id}/edit")
    @Transactional(readOnly = true)
    public String editForm(@PathVariable Long id, Model model) {
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Équipe introuvable : " + id));
        team.getMembers().size();
        team.getRequestTypes().size();
        model.addAttribute("team", team);
        model.addAttribute("internalUsers", userRepository.findInternalUsers());
        model.addAttribute("requestTypes", RequestType.values());
        return "intra/teams/form";
    }

    @PostMapping
    public String save(@RequestParam String name,
                       @RequestParam(required = false) String description,
                       @RequestParam(required = false) List<Long> memberIds,
                       @RequestParam(required = false) List<String> requestTypeNames,
                       RedirectAttributes ra) {
        return persist(new Team(), name, description, memberIds, requestTypeNames, ra);
    }

    @PostMapping("/{id}/update")
    @Transactional
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) List<Long> memberIds,
                         @RequestParam(required = false) List<String> requestTypeNames,
                         RedirectAttributes ra) {
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Équipe introuvable : " + id));
        return persist(team, name, description, memberIds, requestTypeNames, ra);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        teamRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "Équipe supprimée.");
        return "redirect:/intra/teams";
    }

    private String persist(Team team, String name, String description,
                           List<Long> memberIds, List<String> requestTypeNames,
                           RedirectAttributes ra) {
        team.setName(name);
        team.setDescription(description != null && !description.isBlank() ? description : null);

        Set<User> members = new HashSet<>();
        if (memberIds != null) {
            memberIds.forEach(uid -> userRepository.findById(uid).ifPresent(members::add));
        }
        team.setMembers(members);

        Set<RequestType> types = new HashSet<>();
        if (requestTypeNames != null) {
            for (String t : requestTypeNames) {
                try { types.add(RequestType.valueOf(t)); } catch (IllegalArgumentException ignored) {}
            }
        }
        team.setRequestTypes(types);

        teamRepository.save(team);
        ra.addFlashAttribute("successMessage", "Équipe enregistrée.");
        return "redirect:/intra/teams";
    }
}
