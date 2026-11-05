package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Controller
@RequestMapping("/intra/accounting/payfit")
public class PayfitController {

    private final FecImportService fecImportService;
    private final FecExportService fecExportService;
    private final FiscalYearRepository fiscalYearRepo;
    private final AccountingService accountingService;

    public PayfitController(FecImportService fecImportService,
                             FecExportService fecExportService,
                             FiscalYearRepository fiscalYearRepo,
                             AccountingService accountingService) {
        this.fecImportService = fecImportService;
        this.fecExportService = fecExportService;
        this.fiscalYearRepo = fiscalYearRepo;
        this.accountingService = accountingService;
    }

    @GetMapping
    public String dashboard(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = principal.getTenant();
        model.addAttribute("fiscalYears", fiscalYearRepo.findByTenantOrderByStartDateDesc(tenant));
        model.addAttribute("currentYear", accountingService.getCurrentFiscalYear(tenant));
        return "intra/accounting/payfit/dashboard";
    }

    @PostMapping("/import")
    public String importFec(@AuthenticationPrincipal CustomUserDetails principal,
                             @RequestParam("file") MultipartFile file,
                             RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        if (file.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Aucun fichier sélectionné.");
            return "redirect:/intra/accounting/payfit";
        }
        String filename = file.getOriginalFilename();
        if (filename != null && !filename.toLowerCase().endsWith(".txt")
                && !filename.toLowerCase().endsWith(".csv")
                && !filename.toLowerCase().endsWith(".fec")) {
            ra.addFlashAttribute("errorMessage", "Format non supporté. Utilisez un fichier .txt, .fec ou .csv.");
            return "redirect:/intra/accounting/payfit";
        }
        try {
            FecImportService.FecImportResult result = fecImportService.importFec(tenant, file.getInputStream(), filename);
            String msg = "Import FEC terminé — " + result.created() + " écriture(s) créée(s), "
                    + result.skipped() + " ignorée(s), " + result.errors() + " erreur(s).";
            if (!result.errorDetails().isEmpty()) {
                msg += " Détails : " + String.join(" | ", result.errorDetails().stream().limit(3).toList());
            }
            if (result.errors() > 0) {
                ra.addFlashAttribute("errorMessage", msg);
            } else {
                ra.addFlashAttribute("successMessage", msg);
            }
        } catch (IOException e) {
            ra.addFlashAttribute("errorMessage", "Erreur de lecture du fichier : " + e.getMessage());
        }
        return "redirect:/intra/accounting/payfit";
    }

    @GetMapping("/export/fec")
    public ResponseEntity<byte[]> exportFec(@AuthenticationPrincipal CustomUserDetails principal,
                                             @RequestParam(required = false) Long yearId) {
        Tenant tenant = principal.getTenant();
        FiscalYear year = yearId != null
                ? fiscalYearRepo.findById(yearId).orElseGet(() -> accountingService.getCurrentFiscalYear(tenant))
                : accountingService.getCurrentFiscalYear(tenant);

        if (year == null) return ResponseEntity.notFound().build();

        byte[] fec = fecExportService.exportFec(year);
        String filename = "FEC_" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "_" + year.getLabel() + ".txt";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(fec);
    }
}
