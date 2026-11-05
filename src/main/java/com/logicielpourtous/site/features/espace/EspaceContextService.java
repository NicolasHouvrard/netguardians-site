package com.logicielpourtous.site.features.espace;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

@Service
public class EspaceContextService {

    private final CustomerRepository customerRepository;

    public EspaceContextService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public Customer loadCustomer(CustomUserDetails userDetails, HttpSession session) {
        boolean isInternal = userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")
                            || a.getAuthority().equals("ROLE_USER"));
        if (isInternal && session != null) {
            Long viewId = (Long) session.getAttribute("portalViewClientId");
            if (viewId != null) {
                return customerRepository.findById(viewId).orElseThrow(PortalNotLinkedException::new);
            }
        }
        Long customerId = userDetails.getCustomerId();
        if (customerId == null) throw new PortalNotLinkedException();
        return customerRepository.findById(customerId).orElseThrow(PortalNotLinkedException::new);
    }
}
