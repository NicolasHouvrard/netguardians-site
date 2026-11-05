package com.logicielpourtous.site.features.supplier;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class SupplierSyncService {

    private static final Logger log = LoggerFactory.getLogger(SupplierSyncService.class);

    public record SyncResult(int created, int updated, int errors, String message) {}

    private final CustomerRepository    customerRepository;
    private final IngramMicroService    ingramMicroService;

    public SupplierSyncService(CustomerRepository customerRepository,
                               IngramMicroService ingramMicroService) {
        this.customerRepository = customerRepository;
        this.ingramMicroService = ingramMicroService;
    }

    public SyncResult sync(Customer supplier) {
        if (supplier.getSupplierApiType() == null) {
            return new SyncResult(0, 0, 0, "Aucune API configurée pour ce fournisseur.");
        }
        return switch (supplier.getSupplierApiType()) {
            case INGRAM_MICRO -> ingramMicroService.syncCatalog(supplier);
            case TECHDATA     -> syncTechData(supplier);
            case CUSTOM       -> syncCustom(supplier);
            default           -> new SyncResult(0, 0, 0, "Aucune API configurée pour ce fournisseur.");
        };
    }

    private SyncResult syncTechData(Customer supplier) {
        log.info("Synchronisation Tech Data pour le fournisseur id={}", supplier.getId());
        supplier.setSupplierLastSyncAt(LocalDateTime.now());
        customerRepository.save(supplier);
        return new SyncResult(0, 0, 0, "Synchronisation Tech Data — connecteur non implémenté.");
    }

    private SyncResult syncCustom(Customer supplier) {
        log.info("Synchronisation personnalisée pour le fournisseur id={}", supplier.getId());
        supplier.setSupplierLastSyncAt(LocalDateTime.now());
        customerRepository.save(supplier);
        return new SyncResult(0, 0, 0,
                "Synchronisation personnalisée — configurez apiUrl + apiKey.");
    }
}
