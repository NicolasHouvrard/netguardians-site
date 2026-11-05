package com.logicielpourtous.site.features.accounting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class QontoOrganizationDTO {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(
            @JsonProperty("organization") Organization organization
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Organization(
            @JsonProperty("bank_accounts") List<BankAccount> bankAccounts
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BankAccount(
            @JsonProperty("id") String id,
            @JsonProperty("iban") String iban,
            @JsonProperty("bic") String bic,
            @JsonProperty("currency") String currency,
            @JsonProperty("name") String name
    ) {}
}
