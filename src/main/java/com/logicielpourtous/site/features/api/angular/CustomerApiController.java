package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.customers.SiteRepository;
import com.logicielpourtous.site.features.subscription.SubscriptionRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/intra/customers")
public class CustomerApiController {

    private final CustomerRepository     customerRepository;
    private final SiteRepository         siteRepository;
    private final ContactRepository      contactRepository;
    private final SubscriptionRepository subscriptionRepository;

    public CustomerApiController(CustomerRepository customerRepository,
                                 SiteRepository siteRepository,
                                 ContactRepository contactRepository,
                                 SubscriptionRepository subscriptionRepository) {
        this.customerRepository     = customerRepository;
        this.siteRepository         = siteRepository;
        this.contactRepository      = contactRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    public record CustomerPageResponse(
            List<CustomerDto> content,
            int  page,
            int  size,
            long totalElements,
            int  totalPages
    ) {}

    /**
     * GET /api/intra/customers
     *
     * Filtres :
     *  - q        : recherche nom, contact, email
     *  - type     : customer | prospect | supplier | esn
     *  - active   : true | false (absent = tous)
     */
    @GetMapping
    public CustomerPageResponse list(
            @RequestParam(required = false) String  q,
            @RequestParam(required = false) String  type,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Specification<Customer> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (q != null && !q.isBlank()) {
                String pattern = "%" + q.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("companyName")),  pattern),
                        cb.like(cb.lower(root.get("contactName")),  pattern),
                        cb.like(cb.lower(root.get("email")),        pattern)
                ));
            }

            if (type != null) {
                switch (type) {
                    case "customer"  -> predicates.add(cb.equal(root.get("isCustomer"), true));
                    case "prospect"  -> predicates.add(cb.equal(root.get("isProspect"), true));
                    case "supplier"  -> predicates.add(cb.equal(root.get("isSupplier"), true));
                    case "esn"       -> predicates.add(cb.equal(root.get("isEsn"), true));
                }
            }

            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.ASC, "companyName"));
        Page<Customer> result = customerRepository.findAll(spec, pageable);

        return new CustomerPageResponse(
                result.getContent().stream().map(CustomerDto::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    /** GET /api/intra/customers/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<CustomerDto> detail(@PathVariable Long id) {
        return customerRepository.findById(id)
                .map(c -> ResponseEntity.ok(CustomerDto.from(c)))
                .orElse(ResponseEntity.notFound().build());
    }

    public record SaveCustomerRequest(
            String  companyName,
            String  contactName,
            String  email,
            String  phone,
            String  website,
            String  address,
            String  siren,
            String  vatNumber,
            boolean active,
            boolean isCustomer,
            boolean isProspect,
            boolean isSupplier
    ) {}

    private void applyRequest(Customer c, SaveCustomerRequest req) {
        c.setCompanyName(req.companyName());
        c.setContactName(req.contactName());
        c.setEmail(req.email());
        c.setPhone(req.phone());
        c.setWebsite(req.website());
        c.setAddress(req.address());
        c.setSiren(req.siren());
        c.setVatNumber(req.vatNumber());
        c.setActive(req.active());
        c.setCustomer(req.isCustomer());
        c.setProspect(req.isProspect());
        c.setSupplier(req.isSupplier());
    }

    /** POST /api/intra/customers */
    @PostMapping
    public ResponseEntity<CustomerDto> create(@RequestBody SaveCustomerRequest req) {
        Customer c = new Customer();
        applyRequest(c, req);
        return ResponseEntity.ok(CustomerDto.from(customerRepository.save(c)));
    }

    /** PUT /api/intra/customers/{id} */
    @PutMapping("/{id}")
    public ResponseEntity<CustomerDto> update(@PathVariable Long id,
                                              @RequestBody SaveCustomerRequest req) {
        return customerRepository.findById(id)
                .<ResponseEntity<CustomerDto>>map(c -> {
                    applyRequest(c, req);
                    return ResponseEntity.ok(CustomerDto.from(customerRepository.save(c)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Sites CRUD ────────────────────────────────────────────────────────────

    public record SaveSiteRequest(
            String  name,
            String  address,
            String  city,
            String  zipCode,
            String  country,
            boolean active
    ) {}

    private void applySite(com.logicielpourtous.site.features.customers.Site s, SaveSiteRequest r) {
        s.setName(r.name());
        s.setAddress(r.address());
        s.setCity(r.city());
        s.setZipCode(r.zipCode());
        s.setCountry(r.country() != null ? r.country() : "France");
        s.setActive(r.active());
    }

    /** GET /api/intra/customers/{id}/sites */
    @GetMapping("/{id}/sites")
    public ResponseEntity<List<SiteDto>> sites(@PathVariable Long id) {
        if (!customerRepository.existsById(id)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(
                siteRepository.findByCustomerId(id)
                        .stream()
                        .sorted(java.util.Comparator.comparing(s -> s.getName().toLowerCase()))
                        .map(SiteDto::from).toList()
        );
    }

    /** POST /api/intra/customers/{id}/sites */
    @PostMapping("/{id}/sites")
    public ResponseEntity<SiteDto> createSite(@PathVariable Long id,
                                              @RequestBody SaveSiteRequest req) {
        return customerRepository.findById(id).map(customer -> {
            com.logicielpourtous.site.features.customers.Site s =
                    new com.logicielpourtous.site.features.customers.Site();
            s.setCustomer(customer);
            applySite(s, req);
            return ResponseEntity.ok(SiteDto.from(siteRepository.save(s)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** PUT /api/intra/customers/{id}/sites/{siteId} */
    @PutMapping("/{id}/sites/{siteId}")
    public ResponseEntity<SiteDto> updateSite(@PathVariable Long id,
                                              @PathVariable Long siteId,
                                              @RequestBody SaveSiteRequest req) {
        return siteRepository.findById(siteId)
                .filter(s -> s.getCustomer().getId().equals(id))
                .map(s -> { applySite(s, req); return ResponseEntity.ok(SiteDto.from(siteRepository.save(s))); })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** DELETE /api/intra/customers/{id}/sites/{siteId} */
    @DeleteMapping("/{id}/sites/{siteId}")
    public ResponseEntity<Void> deleteSite(@PathVariable Long id, @PathVariable Long siteId) {
        return siteRepository.findById(siteId)
                .filter(s -> s.getCustomer().getId().equals(id))
                .map(s -> { siteRepository.delete(s); return ResponseEntity.ok().<Void>build(); })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Contacts CRUD ─────────────────────────────────────────────────────────

    public record SaveContactRequest(
            String firstName,
            String lastName,
            String email,
            String phone,
            String position,
            Long   siteId
    ) {}

    private void applyContact(com.logicielpourtous.site.features.customers.Contact c,
                              SaveContactRequest r) {
        c.setFirstName(r.firstName());
        c.setLastName(r.lastName());
        c.setEmail(r.email());
        c.setPhone(r.phone());
        c.setPosition(r.position());
        if (r.siteId() != null) {
            siteRepository.findById(r.siteId()).ifPresent(c::setSite);
        } else {
            c.setSite(null);
        }
    }

    /** GET /api/intra/customers/{id}/contacts */
    @GetMapping("/{id}/contacts")
    public ResponseEntity<List<ContactDto>> contacts(@PathVariable Long id) {
        if (!customerRepository.existsById(id)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(
                contactRepository.findByCustomerId(id)
                        .stream()
                        .sorted(java.util.Comparator.comparing(
                                c -> c.getLastName() != null ? c.getLastName().toLowerCase() : ""))
                        .map(ContactDto::from).toList()
        );
    }

    /** POST /api/intra/customers/{id}/contacts */
    @PostMapping("/{id}/contacts")
    public ResponseEntity<ContactDto> createContact(@PathVariable Long id,
                                                    @RequestBody SaveContactRequest req) {
        return customerRepository.findById(id).map(customer -> {
            com.logicielpourtous.site.features.customers.Contact c =
                    new com.logicielpourtous.site.features.customers.Contact();
            c.setCustomer(customer);
            applyContact(c, req);
            return ResponseEntity.ok(ContactDto.from(contactRepository.save(c)));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** PUT /api/intra/customers/{id}/contacts/{contactId} */
    @PutMapping("/{id}/contacts/{contactId}")
    public ResponseEntity<ContactDto> updateContact(@PathVariable Long id,
                                                    @PathVariable Long contactId,
                                                    @RequestBody SaveContactRequest req) {
        return contactRepository.findById(contactId)
                .filter(c -> c.getCustomer() != null && c.getCustomer().getId().equals(id))
                .map(c -> { applyContact(c, req); return ResponseEntity.ok(ContactDto.from(contactRepository.save(c))); })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** DELETE /api/intra/customers/{id}/contacts/{contactId} */
    @DeleteMapping("/{id}/contacts/{contactId}")
    public ResponseEntity<Void> deleteContact(@PathVariable Long id, @PathVariable Long contactId) {
        return contactRepository.findById(contactId)
                .filter(c -> c.getCustomer() != null && c.getCustomer().getId().equals(id))
                .map(c -> { contactRepository.delete(c); return ResponseEntity.ok().<Void>build(); })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** GET /api/intra/customers/{id}/subscriptions */
    @GetMapping("/{id}/subscriptions")
    public ResponseEntity<List<SubscriptionDto>> subscriptions(@PathVariable Long id) {
        if (!customerRepository.existsById(id)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(
                subscriptionRepository.findByCustomerIdOrderByActiveDescStartDateDesc(id)
                        .stream().map(SubscriptionDto::from).toList()
        );
    }
}
