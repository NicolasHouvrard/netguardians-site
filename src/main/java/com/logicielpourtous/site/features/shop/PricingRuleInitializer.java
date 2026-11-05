package com.logicielpourtous.site.features.shop;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class PricingRuleInitializer implements ApplicationRunner {

    private final PricingRuleRepository pricingRuleRepository;

    public PricingRuleInitializer(PricingRuleRepository pricingRuleRepository) {
        this.pricingRuleRepository = pricingRuleRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<PricingRule> rules = pricingRuleRepository.findAllByOrderByNameAsc();

        // Migration : les règles créées avec l'ancien schéma (type/value) ont fixedMargin/percentage null
        boolean needsMigration = rules.stream()
                .anyMatch(r -> r.getFixedMargin() == null && r.getPercentage() == null);

        if (rules.isEmpty() || needsMigration) {
            pricingRuleRepository.deleteAll();
            PricingRule rule = new PricingRule();
            rule.setName("PA + 10 €");
            rule.setFixedMargin(new BigDecimal("10.00"));
            rule.setPercentage(BigDecimal.ZERO);
            rule.setDefault(true);
            pricingRuleRepository.save(rule);
        }
    }
}
