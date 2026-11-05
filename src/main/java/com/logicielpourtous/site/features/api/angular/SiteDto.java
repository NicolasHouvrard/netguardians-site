package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.customers.Site;

public record SiteDto(
        Long   id,
        String name,
        String address,
        String city,
        String zipCode,
        String country,
        boolean active,
        int    contactCount
) {
    public static SiteDto from(Site s) {
        return new SiteDto(
                s.getId(),
                s.getName(),
                s.getAddress(),
                s.getCity(),
                s.getZipCode(),
                s.getCountry(),
                s.isActive(),
                s.getContacts() != null ? s.getContacts().size() : 0
        );
    }
}
