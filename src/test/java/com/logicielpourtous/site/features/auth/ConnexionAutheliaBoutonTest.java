package com.logicielpourtous.site.features.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Le bouton « compte unique » de la page de connexion n'apparait que si le fournisseur authelia est configure. */
@SpringBootTest
@AutoConfigureMockMvc
class ConnexionAutheliaBoutonTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ClientRegistrationRepository registrations;

    @Test
    void le_bouton_authelia_n_apparait_que_si_le_fournisseur_est_configure() throws Exception {
        when(registrations.findByRegistrationId("authelia")).thenReturn(null);
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/oauth2/authorization/authelia"))));

        when(registrations.findByRegistrationId("authelia")).thenReturn(
                ClientRegistration.withRegistrationId("authelia")
                        .clientId("site").clientSecret("pas-un-secret")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                        .authorizationUri("https://auth.example.org/authorize")
                        .tokenUri("https://auth.example.org/token")
                        .build());
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/oauth2/authorization/authelia")))
                .andExpect(content().string(containsString("Se connecter avec le compte unique")));
    }
}
