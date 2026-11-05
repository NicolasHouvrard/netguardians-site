package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.crm.quote.QuoteRepository;
import com.logicielpourtous.site.features.crm.quote.QuoteStatus;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class QuotesEnAttenteWidget implements IntraWidgetProvider {

    private final QuoteRepository quoteRepository;

    public QuotesEnAttenteWidget(QuoteRepository quoteRepository) {
        this.quoteRepository = quoteRepository;
    }

    @Override public String key() { return "QUOTES_EN_ATTENTE"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("value",
                String.valueOf(quoteRepository.countByStatus(QuoteStatus.ENVOYE)));
    }
}
