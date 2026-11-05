package com.logicielpourtous.site.features.site;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.ui.Model;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import  com.logicielpourtous.site.features.module.AdminModule;
import  com.logicielpourtous.site.features.module.AdminModuleRepository;



@Controller
public class DashboardController   {


    private AdminModuleRepository moduleRepository;
    private PageRepository pageRepository;

    public DashboardController(AdminModuleRepository moduleRepository, PageRepository pageRepository){
             this.moduleRepository = moduleRepository;
             this.pageRepository =  pageRepository ;
    }
       
    
    @GetMapping("/dashboard")
    public String showDashboard(Model model) {
        // On récupère tout depuis la BDD au lieu du List.of(...)
        List<AdminModule> allModules = moduleRepository.findAllByOrderByDisplayOrderAsc();

        // On groupe les modules par catégorie : Map<String, List<AdminModule>>
    Map<String, List<AdminModule>> modulesParCategorie = allModules.stream()
        .collect(Collectors.groupingBy(m -> 
            m.getCategory() != null ? m.getCategory() : "Général"));
        
        model.addAttribute("listeModules", modulesParCategorie);
        return "feature/site/dashboard";
    }

    


    
}
