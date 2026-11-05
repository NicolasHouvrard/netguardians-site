package com.logicielpourtous.site.features.shop;

import com.logicielpourtous.site.features.payment.PaymentConfig;
import com.logicielpourtous.site.features.payment.PaymentConfigRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ShopConfigService {

    @Value("${shop.name:Notre Boutique}")
    private String defaultName;

    @Value("${shop.base-url:http://localhost:8080}")
    private String defaultBaseUrl;

    private final ShopConfigRepository shopConfigRepository;
    private final PaymentConfigRepository paymentConfigRepository;

    public ShopConfigService(ShopConfigRepository shopConfigRepository,
                             PaymentConfigRepository paymentConfigRepository) {
        this.shopConfigRepository  = shopConfigRepository;
        this.paymentConfigRepository = paymentConfigRepository;
    }

    public String getShopName() {
        ShopConfig c = shopConfigRepository.findById(1L).orElse(null);
        return (c != null && c.getShopName() != null && !c.getShopName().isBlank())
                ? c.getShopName() : defaultName;
    }

    public String getBaseUrl() {
        ShopConfig c = shopConfigRepository.findById(1L).orElse(null);
        return (c != null && c.getBaseUrl() != null && !c.getBaseUrl().isBlank())
                ? c.getBaseUrl() : defaultBaseUrl;
    }

    public ShopConfig getOrCreate() {
        return shopConfigRepository.findById(1L).orElseGet(() -> {
            ShopConfig c = new ShopConfig();
            c.setShopName(defaultName);
            c.setBaseUrl(defaultBaseUrl);
            return c;
        });
    }

    public PaymentConfig getBankTransferConfig() {
        return paymentConfigRepository.findByType(PaymentConfig.ConfigType.BANK_TRANSFER).orElse(null);
    }

    public String getStripeSecretKey() {
        PaymentConfig pc = paymentConfigRepository.findByType(PaymentConfig.ConfigType.STRIPE).orElse(null);
        return (pc != null && pc.isActive() && pc.getStripeSecretKey() != null)
                ? pc.getStripeSecretKey() : null;
    }

    public void save(String shopName, String baseUrl) {
        ShopConfig c = shopConfigRepository.findById(1L).orElseGet(ShopConfig::new);
        if (shopName != null && !shopName.isBlank()) c.setShopName(shopName);
        if (baseUrl  != null && !baseUrl.isBlank())  c.setBaseUrl(baseUrl);
        shopConfigRepository.save(c);
    }
}
