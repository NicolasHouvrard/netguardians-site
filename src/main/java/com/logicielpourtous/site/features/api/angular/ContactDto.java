package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.customers.Contact;

public record ContactDto(
        Long   id,
        String firstName,
        String lastName,
        String fullName,
        String email,
        String phone,
        String position,
        Long   siteId,
        String siteName,
        Long   userId
) {
    public static ContactDto from(Contact c) {
        return new ContactDto(
                c.getId(),
                c.getFirstName(),
                c.getLastName(),
                c.getFullName().trim(),
                c.getEmail(),
                c.getPhone(),
                c.getPosition(),
                c.getSite() != null ? c.getSite().getId()   : null,
                c.getSite() != null ? c.getSite().getName() : null,
                c.getUserId()
        );
    }
}
