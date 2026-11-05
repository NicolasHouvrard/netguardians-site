package com.logicielpourtous.site.features.intervention;

import com.logicielpourtous.site.features.esn.PartnerEsnRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/intra/marketplace")
public class MarketplaceController {

    private final InterventionRepository interventionRepository;
    private final PartnerEsnRepository   esnRepository;

    public MarketplaceController(InterventionRepository interventionRepository,
                                 PartnerEsnRepository esnRepository) {
        this.interventionRepository = interventionRepository;
        this.esnRepository          = esnRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("published", interventionRepository.findPublished());
        model.addAttribute("esns",      esnRepository.findByActiveTrueOrderByCompanyNameAsc());
        return "intra/marketplace/list";
    }
}
