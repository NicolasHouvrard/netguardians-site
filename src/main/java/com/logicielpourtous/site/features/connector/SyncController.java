package com.logicielpourtous.site.features.connector;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.supplier.IngramMicroService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/intra/sync")
public class SyncController {

    private final PlatformConnectorRepository connectorRepository;
    private final ConnectorSyncService        syncService;
    private final SyncLogRepository           syncLogRepository;
    private final SyncSchedulerService        schedulerService;
    private final IngramMicroService          ingramMicroService;
    private final CustomerRepository          customerRepository;

    public SyncController(PlatformConnectorRepository connectorRepository,
                          ConnectorSyncService syncService,
                          SyncLogRepository syncLogRepository,
                          SyncSchedulerService schedulerService,
                          IngramMicroService ingramMicroService,
                          CustomerRepository customerRepository) {
        this.connectorRepository = connectorRepository;
        this.syncService         = syncService;
        this.syncLogRepository   = syncLogRepository;
        this.schedulerService    = schedulerService;
        this.ingramMicroService  = ingramMicroService;
        this.customerRepository  = customerRepository;
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────

    @GetMapping
    public String dashboard(Model model) {
        List<PlatformConnector> connectors = connectorRepository.findAll(
                Sort.by("name"));

        // Dernière sync par connecteur (HashMap pour accepter les valeurs null)
        java.util.Map<Long, SyncLog> lastLogs = new java.util.HashMap<>();
        for (PlatformConnector c : connectors) {
            lastLogs.put(c.getId(), syncLogRepository.findLastByConnectorId(c.getId()).orElse(null));
        }

        model.addAttribute("connectors", connectors);
        model.addAttribute("lastLogs",   lastLogs);
        return "intra/sync/dashboard";
    }

    // ── Déclenchement manuel ─────────────────────────────────────────────────

    @PostMapping("/{id}/trigger")
    public String trigger(@PathVariable Long id, RedirectAttributes ra) {
        PlatformConnector c = connectorRepository.findById(id).orElse(null);
        if (c == null) {
            ra.addFlashAttribute("errorMessage", "Connecteur introuvable.");
            return "redirect:/intra/sync";
        }

        if (c.getConnectorType() == ConnectorType.INGRAM_MICRO) {
            Customer supplier = resolveIngramSupplier(c);
            if (supplier != null) {
                try {
                    ingramMicroService.syncCatalogFullAsync(supplier.getId(), id, null, null, null);
                    ra.addFlashAttribute("successMessage",
                            "Synchronisation complète lancée en arrière-plan pour \"" + c.getName() + "\".");
                } catch (Exception e) {
                    ra.addFlashAttribute("errorMessage", "Erreur lors du lancement : " + e.getMessage());
                }
            } else {
                ra.addFlashAttribute("errorMessage",
                        "Aucun fournisseur Ingram Micro trouvé avec des credentials valides. "
                        + "Configurez le fournisseur dans /intra/suppliers.");
            }
            return "redirect:/intra/sync";
        }

        // Autres types de connecteurs
        try {
            ConnectorSyncService.SyncResult result =
                    syncService.syncWithLog(id, SyncLog.Trigger.MANUAL);
            ra.addFlashAttribute("successMessage",
                    "Sync terminée : " + result.created() + " créés, "
                    + result.updated() + " mis à jour"
                    + (result.errors() > 0 ? ", " + result.errors() + " erreurs" : "") + ".");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur sync : " + e.getMessage());
        }
        return "redirect:/intra/sync";
    }

    @PostMapping("/{id}/price-trigger")
    public String priceTrigger(@PathVariable Long id, RedirectAttributes ra) {
        PlatformConnector c = connectorRepository.findById(id).orElse(null);
        if (c == null) {
            ra.addFlashAttribute("errorMessage", "Connecteur introuvable.");
            return "redirect:/intra/sync";
        }
        Customer supplier = resolveIngramSupplier(c);
        if (supplier == null) {
            ra.addFlashAttribute("errorMessage",
                    "Aucun fournisseur Ingram Micro trouvé avec des credentials valides.");
            return "redirect:/intra/sync";
        }
        try {
            ingramMicroService.refreshPricesAndStockAsync(supplier.getId(), id);
            ra.addFlashAttribute("successMessage",
                    "Mise à jour prix/stock lancée en arrière-plan pour \"" + c.getName() + "\".");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur : " + e.getMessage());
        }
        return "redirect:/intra/sync";
    }

    /**
     * Trouve le fournisseur Ingram à utiliser pour un connecteur :
     * 1. Si le customer lié est un supplier avec credentials → on l'utilise
     * 2. Sinon : on cherche dans tous les fournisseurs celui qui a un client_id Ingram
     */
    private Customer resolveIngramSupplier(PlatformConnector c) {
        if (c.getCustomer() != null) {
            Customer linked = customerRepository.findById(c.getCustomer().getId()).orElse(null);
            if (linked != null && linked.isSupplier()
                    && linked.getSupplierApiClientId() != null
                    && !linked.getSupplierApiClientId().isBlank()
                    && linked.getSupplierApiKey() != null
                    && !linked.getSupplierApiKey().isBlank()) {
                return linked;
            }
        }
        return customerRepository.findSuppliers().stream()
                .filter(s -> s.getSupplierApiClientId() != null
                        && !s.getSupplierApiClientId().isBlank()
                        && s.getSupplierApiKey() != null
                        && !s.getSupplierApiKey().isBlank())
                .findFirst().orElse(null);
    }

    // ── Historique global ─────────────────────────────────────────────────────

    @GetMapping("/history")
    public String history(@RequestParam(defaultValue = "0") int page,
                          @RequestParam(required = false) Long connectorId,
                          Model model) {
        Page<SyncLog> logs = connectorId != null
                ? syncLogRepository.findByConnectorIdOrderByStartedAtDesc(
                        connectorId, PageRequest.of(page, 50))
                : syncLogRepository.findAllByOrderByStartedAtDesc(
                        PageRequest.of(page, 50));

        model.addAttribute("logs",        logs);
        model.addAttribute("connectors",  connectorRepository.findAll(Sort.by("name")));
        model.addAttribute("connectorId", connectorId);
        return "intra/sync/history";
    }

    // ── Activation/désactivation du cron ─────────────────────────────────────

    @PostMapping("/{id}/schedule")
    public String updateSchedule(@PathVariable Long id,
                                 @RequestParam(required = false) String cronExpression,
                                 @RequestParam(defaultValue = "false") boolean schedulingEnabled,
                                 @RequestParam(required = false) String priceCronExpression,
                                 @RequestParam(defaultValue = "false") boolean priceSchedulingEnabled,
                                 RedirectAttributes ra) {
        PlatformConnector c = connectorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connecteur introuvable"));
        c.setCronExpression(cronExpression != null && !cronExpression.isBlank()
                ? cronExpression.trim() : null);
        c.setSchedulingEnabled(schedulingEnabled);
        c.setPriceCronExpression(priceCronExpression != null && !priceCronExpression.isBlank()
                ? priceCronExpression.trim() : null);
        c.setPriceSchedulingEnabled(priceSchedulingEnabled);
        connectorRepository.save(c);
        schedulerService.reschedule(c);
        ra.addFlashAttribute("successMessage", "Planification enregistrée.");
        return "redirect:/intra/sync";
    }
}
