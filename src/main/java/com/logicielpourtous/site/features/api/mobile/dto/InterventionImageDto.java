package com.logicielpourtous.site.features.api.mobile.dto;

import com.logicielpourtous.site.features.intervention.InterventionImage;

public record InterventionImageDto(
        Long   id,
        String url,
        String originalName,
        String uploadedAt
) {
    public static InterventionImageDto from(InterventionImage img) {
        return new InterventionImageDto(
                img.getId(),
                img.getFilename(),
                img.getOriginalName(),
                img.getUploadedAt() != null ? img.getUploadedAt().toString() : null
        );
    }
}
