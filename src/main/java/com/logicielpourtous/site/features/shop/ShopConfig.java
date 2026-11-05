package com.logicielpourtous.site.features.shop;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "shop_configs")
@Getter @Setter @NoArgsConstructor
public class ShopConfig {

    @Id
    private Long id = 1L;

    @Column(name = "shop_name")
    private String shopName;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}
