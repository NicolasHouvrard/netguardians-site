package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.accounting.AccountingAccountRepository;
import com.logicielpourtous.site.features.accounting.AccountingEntryLineRepository;
import com.logicielpourtous.site.features.accounting.QontoTransactionRepository;
import com.logicielpourtous.site.features.accounting.StripeTransactionRepository;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.intra.widget.WidgetUtils;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TresorerieWidget implements IntraWidgetProvider {

    private final AccountingAccountRepository    accountRepo;
    private final AccountingEntryLineRepository  entryRepo;
    private final QontoTransactionRepository     qontoRepo;
    private final StripeTransactionRepository    stripeRepo;

    public TresorerieWidget(AccountingAccountRepository accountRepo,
                            AccountingEntryLineRepository entryRepo,
                            QontoTransactionRepository qontoRepo,
                            StripeTransactionRepository stripeRepo) {
        this.accountRepo = accountRepo;
        this.entryRepo   = entryRepo;
        this.qontoRepo   = qontoRepo;
        this.stripeRepo  = stripeRepo;
    }

    @Override public String key() { return "TRESORERIE"; }
    @Override public String template() { return "intra/widgets/_bank-accounts"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        Tenant t = user != null ? user.getTenant() : null;
        List<Map<String, Object>> rows = t == null ? List.of() :
                accountRepo.findByTenantAndActiveTrueOrderByNumberAsc(t).stream()
                        .filter(a -> a.getNumber() != null
                                && (a.getNumber().startsWith("51") || a.getNumber().startsWith("53")))
                        .map(acc -> {
                            BigDecimal balance = entryRepo.getBalanceByAccountNumberAndTenant(acc.getNumber(), t);
                            long unreconciled = qontoRepo.countByTenantAndBankAccountNumberAndReconciledFalseAndImportStatus(t, acc.getNumber(), "IMPORTED")
                                    + stripeRepo.countByTenantAndBankAccountNumberAndReconciledFalseAndStripeStatus(t, acc.getNumber(), "IMPORTED");
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("label", acc.getLabel() != null ? acc.getLabel() : acc.getNumber());
                            row.put("number", acc.getNumber());
                            row.put("balance", balance != null ? balance : BigDecimal.ZERO);
                            row.put("unreconciled", unreconciled);
                            return row;
                        })
                        .collect(Collectors.toList());
        model.addAttribute("rows", rows);
    }
}
