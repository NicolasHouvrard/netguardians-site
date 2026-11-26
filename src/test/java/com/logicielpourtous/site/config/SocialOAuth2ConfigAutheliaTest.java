package com.logicielpourtous.site.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import com.logicielpourtous.site.features.mail.MailAccountRepository;

/** Sans proprietes completes, le fournisseur authelia n'existe pas : ni erreur, ni appel reseau. */
class SocialOAuth2ConfigAutheliaTest {

    @Test
    void sans_proprietes_authelia_est_absent() {
        ClientRegistrationRepository repo = new SocialOAuth2Config(mock(MailAccountRepository.class), new MockEnvironment())
                .clientRegistrationRepository();
        assertThat(repo.findByRegistrationId("authelia")).isNull();
        assertThat(repo.findByRegistrationId("google")).isNull();
    }

    @Test
    void proprietes_vides_ou_partielles_authelia_est_absent() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.security.oauth2.client.registration.authelia.client-id", "site")
                .withProperty("spring.security.oauth2.client.registration.authelia.client-secret", "")
                .withProperty("spring.security.oauth2.client.provider.authelia.issuer-uri", "");
        ClientRegistrationRepository repo = new SocialOAuth2Config(mock(MailAccountRepository.class), env)
                .clientRegistrationRepository();
        assertThat(repo.findByRegistrationId("authelia")).isNull();
    }
}
