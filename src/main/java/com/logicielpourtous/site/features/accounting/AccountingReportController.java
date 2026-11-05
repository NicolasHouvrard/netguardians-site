package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/accounting/reports")
public class AccountingReportController {

    private final AccountingAccountRepository accountRepo;
    private final AccountingEntryLineRepository lineRepo;
    private final FiscalYearRepository yearRepo;
    private final AccountingService service;
    private final AccountingFinancialStatementService stmtService;

    public AccountingReportController(AccountingAccountRepository accountRepo,
                                      AccountingEntryLineRepository lineRepo,
                                      FiscalYearRepository yearRepo,
                                      AccountingService service,
                                      AccountingFinancialStatementService stmtService) {
        this.accountRepo = accountRepo;
        this.lineRepo = lineRepo;
        this.yearRepo = yearRepo;
        this.service = service;
        this.stmtService = stmtService;
    }

    @GetMapping("/balance")
    public String balance(@AuthenticationPrincipal CustomUserDetails principal,
                          @RequestParam(required = false) Long yearId, Model model) {
        Tenant tenant = principal.getTenant();
        FiscalYear year = resolveYear(tenant, yearId);
        if (year == null) return "redirect:/intra/accounting/fiscal-years";
        List<AccountingService.BalanceLine> lines = service.getBalance(year, tenant);
        BigDecimal sumDebit  = lines.stream().map(AccountingService.BalanceLine::totalDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCredit = lines.stream().map(AccountingService.BalanceLine::totalCredit).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Group by account class (sorted); compute per-class subtotals
        TreeMap<Integer, List<AccountingService.BalanceLine>> linesByClass = lines.stream()
                .collect(Collectors.groupingBy(bl -> bl.account().getAccountClass(), TreeMap::new, Collectors.toList()));
        Map<Integer, BigDecimal[]> subtotals = new LinkedHashMap<>();
        linesByClass.forEach((cls, cls_lines) -> {
            BigDecimal d = cls_lines.stream().map(AccountingService.BalanceLine::totalDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal c = cls_lines.stream().map(AccountingService.BalanceLine::totalCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
            subtotals.put(cls, new BigDecimal[]{d, c});
        });

        model.addAttribute("year", year);
        model.addAttribute("lines", lines);
        model.addAttribute("linesByClass", linesByClass);
        model.addAttribute("subtotals", subtotals);
        model.addAttribute("sumDebit", sumDebit);
        model.addAttribute("sumCredit", sumCredit);
        model.addAttribute("fiscalYears", yearRepo.findByTenantOrderByStartDateDesc(tenant));
        return "intra/accounting/reports/balance";
    }

    @GetMapping("/grand-livre")
    public String grandLivre(@AuthenticationPrincipal CustomUserDetails principal,
                              @RequestParam(required = false) Long accountId,
                              @RequestParam(required = false) Long yearId,
                              Model model) {
        Tenant tenant = principal.getTenant();
        FiscalYear year = resolveYear(tenant, yearId);
        model.addAttribute("year", year);
        model.addAttribute("accounts", accountRepo.findByTenantAndActiveTrueOrderByNumberAsc(tenant));
        model.addAttribute("fiscalYears", yearRepo.findByTenantOrderByStartDateDesc(tenant));

        if (accountId != null && year != null) {
            accountRepo.findById(accountId).ifPresent(account -> {
                model.addAttribute("selectedAccount", account);
                List<AccountingEntryLine> rawLines = lineRepo.findByAccountAndFiscalYear(account, year);
                BigDecimal runDebit  = BigDecimal.ZERO;
                BigDecimal runCredit = BigDecimal.ZERO;
                List<GrandLivreLine> glLines = new java.util.ArrayList<>();
                for (AccountingEntryLine l : rawLines) {
                    runDebit  = runDebit.add(l.getDebit());
                    runCredit = runCredit.add(l.getCredit());
                    glLines.add(new GrandLivreLine(l, runDebit, runCredit, runDebit.subtract(runCredit)));
                }
                model.addAttribute("lines", glLines);
                model.addAttribute("totalDebit",  runDebit);
                model.addAttribute("totalCredit", runCredit);
                model.addAttribute("solde", runDebit.subtract(runCredit));
            });
        }
        return "intra/accounting/reports/grand-livre";
    }

    @GetMapping("/bilan")
    public String bilan(@AuthenticationPrincipal CustomUserDetails principal,
                        @RequestParam(required = false) Long yearId, Model model) {
        Tenant tenant = principal.getTenant();
        FiscalYear year = resolveYear(tenant, yearId);
        if (year == null) return "redirect:/intra/accounting/fiscal-years";
        List<AccountingService.BalanceLine> balanceLines = service.getBalance(year, tenant);
        AccountingFinancialStatementService.Bilan bilan = stmtService.buildBilan(balanceLines);
        model.addAttribute("year", year);
        model.addAttribute("fiscalYears", yearRepo.findByTenantOrderByStartDateDesc(tenant));
        model.addAttribute("bilan", bilan);
        return "intra/accounting/reports/bilan";
    }

    @GetMapping("/compte-resultat")
    public String compteResultat(@AuthenticationPrincipal CustomUserDetails principal,
                                 @RequestParam(required = false) Long yearId, Model model) {
        Tenant tenant = principal.getTenant();
        FiscalYear year = resolveYear(tenant, yearId);
        if (year == null) return "redirect:/intra/accounting/fiscal-years";
        List<AccountingService.BalanceLine> balanceLines = service.getBalance(year, tenant);
        AccountingFinancialStatementService.CompteResultat cr = stmtService.buildCompteResultat(balanceLines);
        model.addAttribute("year", year);
        model.addAttribute("fiscalYears", yearRepo.findByTenantOrderByStartDateDesc(tenant));
        model.addAttribute("cr", cr);
        return "intra/accounting/reports/compte-resultat";
    }

    @GetMapping("/livre-des-tiers")
    public String livreDesTors(@AuthenticationPrincipal CustomUserDetails principal,
                               @RequestParam(required = false) Long yearId,
                               @RequestParam(defaultValue = "41") String accountPrefix,
                               Model model) {
        Tenant tenant = principal.getTenant();
        FiscalYear year = resolveYear(tenant, yearId);
        model.addAttribute("year", year);
        model.addAttribute("fiscalYears", yearRepo.findByTenantOrderByStartDateDesc(tenant));
        model.addAttribute("accountPrefix", accountPrefix);

        if (year != null) {
            List<AccountingEntryLine> rawLines = lineRepo.findForLivreDesTiers(year, tenant, accountPrefix);

            Map<String, List<AccountingEntryLine>> grouped = rawLines.stream()
                    .collect(Collectors.groupingBy(
                            l -> l.getEntry().getPartner() != null ? l.getEntry().getPartner() : "—",
                            java.util.LinkedHashMap::new,
                            Collectors.toList()
                    ));

            List<TiersBlock> blocks = new java.util.ArrayList<>();
            for (Map.Entry<String, List<AccountingEntryLine>> e : grouped.entrySet()) {
                BigDecimal runD = BigDecimal.ZERO;
                BigDecimal runC = BigDecimal.ZERO;
                List<GrandLivreLine> glLines = new java.util.ArrayList<>();
                for (AccountingEntryLine l : e.getValue()) {
                    runD = runD.add(l.getDebit());
                    runC = runC.add(l.getCredit());
                    glLines.add(new GrandLivreLine(l, runD, runC, runD.subtract(runC)));
                }
                blocks.add(new TiersBlock(e.getKey(), glLines, runD, runC, runD.subtract(runC)));
            }

            BigDecimal grandTotalD = blocks.stream().map(TiersBlock::totalDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal grandTotalC = blocks.stream().map(TiersBlock::totalCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
            model.addAttribute("blocks", blocks);
            model.addAttribute("grandTotalDebit", grandTotalD);
            model.addAttribute("grandTotalCredit", grandTotalC);
            model.addAttribute("grandSolde", grandTotalD.subtract(grandTotalC));
        }

        return "intra/accounting/reports/livre-des-tiers";
    }

    private FiscalYear resolveYear(Tenant tenant, Long yearId) {
        if (yearId != null) return yearRepo.findById(yearId).orElse(service.getCurrentFiscalYear(tenant));
        return service.getCurrentFiscalYear(tenant);
    }

    public record GrandLivreLine(AccountingEntryLine line, BigDecimal runDebit, BigDecimal runCredit, BigDecimal runSolde) {}
    public record TiersBlock(String partner, List<GrandLivreLine> lines, BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal solde) {}
}
