package com.logicielpourtous.site.features.api.mobile.dto;

public record InterventionSummaryDto(
    Long id,
    String reference,
    String title,
    String status,
    String statusLabel,
    String statusBadgeCss,
    String type,
    String typeLabel,
    String date,
    String scheduledStart,
    String scheduledEnd,
    String customerName,
    String address,
    String city,
    String zipCode
) {}
