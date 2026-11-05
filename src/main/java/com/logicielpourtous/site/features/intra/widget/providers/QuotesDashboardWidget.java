package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.crm.quote.QuoteLineRepository;
import com.logicielpourtous.site.features.crm.quote.QuoteRepository;
import com.logicielpourtous.site.features.crm.quote.QuoteStatus;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.intra.widget.SubValue;
import com.logicielpourtous.site.features.intra.widget.WidgetUtils;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;

@Component
public class QuotesDashboardWidget implements IntraWidgetProvider {

    private final QuoteRepository     quoteRepository;
    private final QuoteLineRepository quoteLineRepository;

    public QuotesDashboardWidget(QuoteRepository quoteRepository,
                                 QuoteLineRepository quoteLineRepository) {
        this.quoteRepository     = quoteRepository;
        this.quoteLineRepository = quoteLineRepository;
    }

    @Override public String key()      { return "QUOTES_DASHBOARD"; }
    @Override public String template() { return "intra/widgets/_multi"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("subValues", List.of(
                new SubValue("En attente HT",  WidgetUtils.formatAmount(quoteLineRepository.sumTotalHTByQuoteStatuses(List.of(QuoteStatus.BROUILLON, QuoteStatus.ENVOYE))), "#0ea5e9"),
                new SubValue("Brouillon",      String.valueOf(quoteRepository.countByStatus(QuoteStatus.BROUILLON)), "#94a3b8"),
                new SubValue("Envoyés",        String.valueOf(quoteRepository.countByStatus(QuoteStatus.ENVOYE)),    "#0ea5e9"),
                new SubValue("Acceptés",       String.valueOf(quoteRepository.countByStatus(QuoteStatus.ACCEPTE)),   "#10b981"),
                new SubValue("Refusés",        String.valueOf(quoteRepository.countByStatus(QuoteStatus.REFUSE)),    "#ef4444"),
                new SubValue("Expirés",        String.valueOf(quoteRepository.countByStatus(QuoteStatus.EXPIRE)),    "#6b7280")
        ));
    }
}
