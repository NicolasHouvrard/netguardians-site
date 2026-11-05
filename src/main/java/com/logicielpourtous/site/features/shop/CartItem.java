package com.logicielpourtous.site.features.shop;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class CartItem implements Serializable {

    public Long shopProductId;
    public String productName;
    public String slug;
    public BigDecimal unitPriceHT;
    public BigDecimal taxRate;
    public int quantity;
    public String featuredImagePath;

    public CartItem() {}

    public CartItem(ShopProduct p, int quantity) {
        this.shopProductId    = p.getId();
        this.productName      = p.getDisplayTitle();
        this.slug             = p.getSlug();
        this.unitPriceHT      = p.getEffectivePriceHT();
        this.taxRate          = p.getEffectiveTaxRate();
        this.quantity         = quantity;
        this.featuredImagePath = p.getFeaturedImagePath();
    }

    public BigDecimal getLineTotalHT() {
        return unitPriceHT.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getVatAmount() {
        return getLineTotalHT().multiply(taxRate.divide(new BigDecimal("100"))).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getLineTotalTTC() {
        return getLineTotalHT().add(getVatAmount()).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getUnitPriceTTC() {
        BigDecimal coeff = BigDecimal.ONE.add(taxRate.divide(new BigDecimal("100")));
        return unitPriceHT.multiply(coeff).setScale(2, RoundingMode.HALF_UP);
    }
}
