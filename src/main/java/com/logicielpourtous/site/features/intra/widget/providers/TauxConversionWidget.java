package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.crm.quote.QuoteRepository;
import com.logicielpourtous.site.features.crm.quote.QuoteStatus;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class TauxConversionWidget implements IntraWidgetProvider {

    private final QuoteRepository quoteRepository;

    public TauxConversionWidget(QuoteRepository quoteRepository) {
        this.quoteRepository = quoteRepository;
    }

    @Override public String key() { return "TAUX_CONVERSION"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        long total    = quoteRepository.count();
        long accepted = quoteRepository.countAccepted();
        String value  = total == 0 ? "—"
                : String.format("%.0f %%", accepted * 100.0 / total);
        model.addAttribute("value", value);
    }
}
