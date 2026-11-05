package com.logicielpourtous.site.features.api.mobile.dto;

public record CloseInterventionRequest(
    String resolution,
    Integer durationMinutes,
    String actualStart,
    String actualEnd,
    Boolean successful,
    String technicianSignature,
    String technicianSignatureName,
    String clientSignature,
    String clientSignatureName
) {}
