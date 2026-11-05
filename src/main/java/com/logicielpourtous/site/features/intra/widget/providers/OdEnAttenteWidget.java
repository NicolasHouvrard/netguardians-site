package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.accounting.AccountingBatchRepository;
import com.logicielpourtous.site.features.accounting.AccountingEntryRepository;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class OdEnAttenteWidget implements IntraWidgetProvider {

    private final AccountingBatchRepository batchRepo;
    private final AccountingEntryRepository entryRepo;

    public OdEnAttenteWidget(AccountingBatchRepository batchRepo,
                             AccountingEntryRepository entryRepo) {
        this.batchRepo = batchRepo;
        this.entryRepo = entryRepo;
    }

    @Override public String key() { return "OD_EN_ATTENTE"; }
    @Override public String template() { return "intra/widgets/_batches"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        Tenant t = user != null ? user.getTenant() : null;
        if (t == null) {
            model.addAttribute("draftCount",  0L);
            model.addAttribute("postedCount", 0L);
            model.addAttribute("rows",        List.of());
            return;
        }

        long draftCount  = batchRepo.countByTenantAndStatus(t, "DRAFT");
        long postedCount = batchRepo.countByTenantAndStatus(t, "POSTED");

        List<Map<String, Object>> rows = batchRepo.findByTenantOrderByCreatedAtDesc(t).stream()
                .limit(6)
                .map(b -> {
                    long entryCount = entryRepo.countByBatchId(b.getId());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",         b.getId());
                    row.put("name",       b.getName());
                    row.put("batchDate",  b.getBatchDate());
                    row.put("status",     b.getStatus());
                    row.put("entryCount", entryCount);
                    return row;
                })
                .collect(Collectors.toList());

        model.addAttribute("draftCount",  draftCount);
        model.addAttribute("postedCount", postedCount);
        model.addAttribute("rows",        rows);
    }
}
