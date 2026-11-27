package com.logicielpourtous.site.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Le filtre captcha, exerce directement (MockMvc laisse le chemin de servlet vide et le saute). */
class CaptchaFilterSecoursTest {

    private final CaptchaService captcha = mock(CaptchaService.class);
    private final CaptchaFilter filtre = new CaptchaFilter(captcha);

    private MockHttpServletResponse poster(String secours) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
        req.setServletPath("/login");
        if (secours != null) {
            req.setParameter("secours", secours);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filtre.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void captcha_refuse_depuis_secours_revient_sur_login_secours_error_captcha() throws Exception {
        when(captcha.verify(any(), any())).thenReturn(false);
        assertThat(poster("1").getRedirectedUrl()).isEqualTo("/login/secours?error=captcha");
    }

    @Test
    void captcha_refuse_sans_secours_revient_sur_login_error_captcha() throws Exception {
        when(captcha.verify(any(), any())).thenReturn(false);
        assertThat(poster(null).getRedirectedUrl()).isEqualTo("/login?error=captcha");
    }

    @Test
    void secours_http_evil_ne_redirige_pas_ailleurs() throws Exception {
        when(captcha.verify(any(), any())).thenReturn(false);
        assertThat(poster("http://evil.example").getRedirectedUrl()).isEqualTo("/login?error=captcha");
    }

    @Test
    void un_post_login_avec_captcha_valide_passe_toujours() throws Exception {
        when(captcha.verify(any(), any())).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
        req.setServletPath("/login");
        req.setParameter("secours", "1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chaine = new MockFilterChain();
        filtre.doFilter(req, res, chaine);
        assertThat(res.getRedirectedUrl()).isNull();
        assertThat(chaine.getRequest()).isNotNull();
        verify(captcha).verify(any(), any());
    }
}
