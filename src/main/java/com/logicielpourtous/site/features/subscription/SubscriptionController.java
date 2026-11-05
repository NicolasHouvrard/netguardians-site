package com.logicielpourtous.site.features.subscription;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.ninja.NinjaOneClient;
import com.logicielpourtous.site.features.ninja.NinjaOneConfig;
import com.logicielpourtous.site.features.ninja.NinjaOneConfigRepository;
import com.logicielpourtous.site.features.ninja.NinjaOrganization;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/intra/customers/{customerId}/subscriptions")
public class SubscriptionController {

    private final SubscriptionRepository         subscriptionRepository;
    private final CustomerRepository             customerRepository;
    private final SubscriptionPlanRepository     planRepository;
    private final SubscriptionCategoryRepository categoryRepository;
    private final NinjaOneConfigRepository       ninjaConfigRepository;
    private final NinjaOneClient                 ninjaOneClient;

    public SubscriptionController(SubscriptionRepository subscriptionRepository,
                                   CustomerRepository customerRepository,
                                   SubscriptionPlanRepository planRepository,
                                   SubscriptionCategoryRepository categoryRepository,
                                   NinjaOneConfigRepository ninjaConfigRepository,
                                   NinjaOneClient ninjaOneClient) {
        this.subscriptionRepository = subscriptionRepository;
        this.customerRepository     = customerRepository;
        this.planRepository         = planRepository;
        this.categoryRepository     = categoryRepository;
        this.ninjaConfigRepository  = ninjaConfigRepository;
        this.ninjaOneClient         = ninjaOneClient;
    }

    @InitBinder("subscription")
    public void initBinder(WebDataBinder binder) {
        binder.setDisallowedFields("billingModel", "period");
    }

    @GetMapping("/list")
    public String list(@PathVariable Long customerId, Model model) {
        addCustomer(model, customerId);
        return "feature/customers/subscriptions/_fragments :: subscriptions-list";
    }

    @GetMapping("/new")
    public String newForm(@PathVariable Long customerId, Model model) {
        addCustomer(model, customerId);
        model.addAttribute("subscription", new Subscription());
        model.addAttribute("plans", planRepository.findByActiveTrueOrdered());
        model.addAttribute("categories", categoryRepository.findByActiveTrueOrderBySortOrderAscLabelAsc());
        return "feature/customers/subscriptions/_fragments :: subscription-form";
    }

    @GetMapping("/edit/{id}")
    public String editForm(@PathVariable Long customerId,
                           @PathVariable Long id,
                           Model model) {
        addCustomer(model, customerId);
        Subscription sub = subscriptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Abonnement introuvable : " + id));
        model.addAttribute("subscription", sub);
        model.addAttribute("plans", planRepository.findByActiveTrueOrdered());
        model.addAttribute("categories", categoryRepository.findByActiveTrueOrderBySortOrderAscLabelAsc());
        return "feature/customers/subscriptions/_fragments :: subscription-form";
    }

    @GetMapping("/plan-fields")
    public String planFields(@PathVariable Long customerId,
                             @RequestParam(required = false) Long planId,
                             Model model) {
        Subscription sub = new Subscription();
        if (planId != null) {
            planRepository.findById(planId).ifPresent(sub::setPlan);
        }
        model.addAttribute("subscription", sub);
        model.addAttribute("periods", SubscriptionPeriod.values());
        return "feature/customers/subscriptions/_fragments :: plan-fields";
    }

    @PostMapping("/save")
    public String save(@PathVariable Long customerId,
                       @ModelAttribute Subscription subscription,
                       @RequestParam(required = false) Long planId,
                       @RequestParam(required = false) String billingModel,
                       @RequestParam(required = false) String period,
                       @RequestParam(required = false, defaultValue = "0") Integer includedHours,
                       @RequestParam(required = false, defaultValue = "0") Integer includedMins,
                       HttpServletResponse response,
                       Model model) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Client introuvable : " + customerId));
        subscription.setCustomer(customer);

        boolean isNew = (subscription.getId() == null);

        SubscriptionPlan plan = null;
        if (planId != null) {
            plan = planRepository.findById(planId).orElse(null);
            if (plan != null) subscription.setPlan(plan);
        }

        subscription.setBillingModel(billingModel != null && !billingModel.isBlank()
                ? SubscriptionBillingModel.valueOf(billingModel) : null);
        subscription.setPeriod(period != null && !period.isBlank()
                ? SubscriptionPeriod.valueOf(period) : null);

        int totalMinutes = includedHours * 60 + includedMins;
        subscription.setIncludedMinutes(totalMinutes > 0 ? totalMinutes : null);

        SubscriptionBillingModel effective = subscription.getEffectiveBillingModel();
        if (effective == SubscriptionBillingModel.FORFAIT) {
            subscription.setHourlyRate(null);
            subscription.setUnitCount(null);
            subscription.setUnitPrice(null);
        } else if (effective == SubscriptionBillingModel.HOURLY) {
            subscription.setIncludedMinutes(null);
            subscription.setPricePerPeriod(null);
            subscription.setPeriod(null);
            subscription.setUnitCount(null);
            subscription.setUnitPrice(null);
        } else {
            subscription.setIncludedMinutes(null);
            subscription.setPricePerPeriod(null);
            subscription.setHourlyRate(null);
        }

        subscriptionRepository.save(subscription);

        // Auto-provision NinjaOne org for new subscriptions on NinjaOne-enabled plans
        if (isNew && plan != null && plan.isNinjaOneIntegration()) {
            provisionNinjaOrg(customer, response);
        }

        response.setHeader("HX-Trigger-After-Settle", "closeSubscriptionModal, refreshSubscriptions");
        return list(customerId, model);
    }

    private void provisionNinjaOrg(Customer customer, HttpServletResponse response) {
        if (customer.getNinjaOrganizationId() != null) {
            // Already linked — ESN must handle this manually on the customer detail page
            response.setHeader("HX-Trigger",
                    "ninjaOrgAlreadyExists");
            return;
        }
        try {
            NinjaOneConfig cfg = ninjaConfigRepository.findById(1L).orElse(null);
            if (cfg == null || !cfg.isEnabled()) return;

            NinjaOrganization org = ninjaOneClient.createOrganization(cfg, customer.getCompanyName());
            if (org != null && org.getId() != null) {
                customer.setNinjaOrganizationId(org.getId());
                customerRepository.save(customer);
            }
        } catch (Exception ignored) {
            // Non-fatal: ESN can link manually via customer detail page
        }
    }

    @DeleteMapping("/delete/{id}")
    public String delete(@PathVariable Long customerId,
                         @PathVariable Long id,
                         Model model) {
        subscriptionRepository.deleteById(id);
        return list(customerId, model);
    }

    private void addCustomer(Model model, Long customerId) {
        model.addAttribute("customer",
                customerRepository.findById(customerId)
                        .orElseThrow(() -> new IllegalArgumentException("Client introuvable : " + customerId)));
    }
}
