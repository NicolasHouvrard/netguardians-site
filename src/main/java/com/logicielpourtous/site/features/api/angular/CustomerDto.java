package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.customers.Customer;

public record CustomerDto(
        Long    id,
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
        boolean isSupplier,
        boolean isProspect,
        boolean isEsn,
        String  prospectStatus
) {
    public static CustomerDto from(Customer c) {
        return new CustomerDto(
                c.getId(),
                c.getCompanyName(),
                c.getContactName(),
                c.getEmail(),
                c.getPhone(),
                c.getWebsite(),
                c.getAddress(),
                c.getSiren(),
                c.getVatNumber(),
                c.isActive(),
                c.isCustomer(),
                c.isSupplier(),
                c.isProspect(),
                c.isEsn(),
                c.isProspect() && c.getProspectStatus() != null
                        ? c.getProspectStatus().name() : null
        );
    }
}
