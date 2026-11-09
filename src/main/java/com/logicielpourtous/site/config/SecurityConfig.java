package com.logicielpourtous.site.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;

import com.logicielpourtous.site.features.auth.CustomTenantSuccessHandler;
import com.logicielpourtous.site.features.auth.CustomUserDetailsService;
import com.logicielpourtous.site.features.auth.OidcSocialLoginUserService;
import com.logicielpourtous.site.features.auth.SelectionSocieteFilter;
import com.logicielpourtous.site.features.auth.SocialLoginUserService;
import com.logicielpourtous.site.features.licence.LicenceApiKeyAuthFilter;
import com.logicielpourtous.site.features.licence.LicenceServeurRepository;
import com.logicielpourtous.site.features.sync.SecondaryServerRepository;
import com.logicielpourtous.site.features.sync.SyncApiKeyAuthFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import java.util.List;
import java.util.Set;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Cle du remember-me de la chaine web. Publique pour que le selecteur de societe puisse remplacer une
     * authentification remember-me par une autre de meme nature (sans la promouvoir en authentification complete).
     */
    public static final String CLE_REMEMBER_ME = "Logi2025";

    private final CustomUserDetailsService customUserDetailsService;
    private final CustomTenantSuccessHandler successHandler;
    private final JwtTokenProvider jwtTokenProvider;
    private final SocialLoginUserService socialLoginUserService;
    private final OidcSocialLoginUserService oidcSocialLoginUserService;
    private final CaptchaFilter captchaFilter;
    private final com.logicielpourtous.site.features.audit.AuthFailureHandler authFailureHandler;

    public SecurityConfig(CustomUserDetailsService customUserDetailsService,
                          CustomTenantSuccessHandler successHandler,
                          JwtTokenProvider jwtTokenProvider,
                          SocialLoginUserService socialLoginUserService,
                          OidcSocialLoginUserService oidcSocialLoginUserService,
                          CaptchaFilter captchaFilter,
                          com.logicielpourtous.site.features.audit.AuthFailureHandler authFailureHandler) {
        this.customUserDetailsService    = customUserDetailsService;
        this.successHandler              = successHandler;
        this.jwtTokenProvider            = jwtTokenProvider;
        this.socialLoginUserService      = socialLoginUserService;
        this.oidcSocialLoginUserService  = oidcSocialLoginUserService;
        this.captchaFilter               = captchaFilter;
        this.authFailureHandler          = authFailureHandler;
    }

    // ── Sync API chain (stateless, cle API) — /api/sync/** ───────────────────
    // Authentification entierement portee par X-Sync-Api-Key (SyncApiKeyAuthFilter), ni JWT
    // ni session ni formulaire. Appels serveur-a-serveur : pas de CORS. Echec -> 401 (pas de
    // redirection /login).

    @Bean
    @Order(0)
    public SecurityFilterChain syncApiFilterChain(HttpSecurity http,
                                                  SecondaryServerRepository secondaryServerRepository) throws Exception {
        http
            .securityMatcher("/api/sync/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/sync/enroll").permitAll()
                .requestMatchers("/api/sync/events").hasRole(SyncApiKeyAuthFilter.ROLE)
                .anyRequest().denyAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(
                new SyncApiKeyAuthFilter(secondaryServerRepository),
                UsernamePasswordAuthenticationFilter.class
            );
        return http.build();
    }

    // ── Licence API chain (stateless, cle API dediee) — /api/licence/** ──────────
    // Authentification entierement portee par X-Licence-Api-Key (LicenceApiKeyAuthFilter) sauf pour
    // les deux routes d'enrolement (la preuve y est dans le corps de la requete, comme
    // /api/sync/enroll). Deliberement SEPAREE de syncApiFilterChain : une cle de ce module ne doit
    // jamais authentifier /api/sync/**, et reciproquement (voir LicenceCles). Meme patron a 3 niveaux
    // que syncApiFilterChain : permitAll() sur les 2 routes d'enrolement, hasRole(...) sur les routes
    // specifiques qui en ont besoin (POST /api/licence/jetons, reservee aux cles actives — le
    // controleur verifie en plus que l'appelant est bien un PARTNER), anyRequest().denyAll()
    // en repli PAR DEFAUT (pas hasRole) : une future route ajoutee sous /api/licence/** sans etre
    // explicitement matchee reste refusee plutot que silencieusement ouverte a toute cle active.
    //
    // @Order(-1) et PAS @Order(4) : @Order(3) (filterChain, juste en-dessous) n'a PAS de
    // securityMatcher — son matcher est "any request" — donc TOUT chain d'ordre superieur a 3 est
    // structurellement inatteignable (Spring Security refuse meme de demarrer : "A filter chain
    // that matches any request ... will never get invoked", verifie en lancant les tests). Toute
    // nouvelle chaine a securityMatcher doit donc avoir un ordre strictement inferieur a 3 ; 0/1/2
    // etant deja pris, -1 est la valeur libre la plus proche. L'ordre relatif entre -1, 0, 1 et 2
    // n'a sinon aucune importance ici : leurs securityMatcher respectifs sont mutuellement exclusifs.
    @Bean
    @Order(-1)
    public SecurityFilterChain licenceApiFilterChain(HttpSecurity http,
                                                      LicenceServeurRepository licenceServeurRepository) throws Exception {
        http
            .securityMatcher("/api/licence/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/licence/enregistrer", "/api/licence/cle").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/licence/jetons").hasRole(LicenceApiKeyAuthFilter.ROLE)
                // Task 6 : sondage de decouverte (GET) et push-back du code local (POST), tous deux
                // reserves aux cles actives — le controleur verifie en plus que l'appelant est bien un
                // PARTNER (meme patron que /jetons).
                .requestMatchers(HttpMethod.GET, "/api/licence/nouveaux-serveurs").hasRole(LicenceApiKeyAuthFilter.ROLE)
                .requestMatchers(HttpMethod.POST, "/api/licence/code-partenaire").hasRole(LicenceApiKeyAuthFilter.ROLE)
                // Task 3 : l'Agent de deploiement (via la cle API de CE serveur) sonde son ordre de
                // deploiement en attente — meme garde que les autres routes reservees aux cles actives.
                .requestMatchers(HttpMethod.GET, "/api/licence/ordre-deploiement").hasRole(LicenceApiKeyAuthFilter.ROLE)
                .anyRequest().denyAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(
                new LicenceApiKeyAuthFilter(licenceServeurRepository),
                UsernamePasswordAuthenticationFilter.class
            );
        return http.build();
    }

    // ── Angular API chain (stateless JWT) — /api/intra/** + /api/espace/** ──────

    @Bean
    @Order(1)
    public SecurityFilterChain angularApiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/auth/**", "/api/intra/**", "/api/espace/**")
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/providers", "/api/auth/google").permitAll()
                // Choix de la societe active : tout compte authentifie, y compris en attente de choix (seule
                // route qu'ouvre le jeton de selection, voir JwtAuthenticationFilter).
                .requestMatchers(HttpMethod.POST, JwtAuthenticationFilter.CHOIX_SOCIETE).authenticated()
                .requestMatchers("/api/intra/admin/**").hasRole("ADMIN")
                // ESN_USER est une autorité synthétique posée par CustomUserDetails
                // pour TOUS les rôles de type ESN (y compris les rôles custom par tenant).
                .requestMatchers("/api/intra/**").hasAnyRole("ADMIN","ESN_USER")
                .requestMatchers("/api/espace/**").hasAnyRole("ADMIN","CLIENT","GESTIONNAIRE")
                .anyRequest().access(authentifieHorsSelection())
            )
            .addFilterBefore(
                new JwtAuthenticationFilter(jwtTokenProvider, customUserDetailsService),
                UsernamePasswordAuthenticationFilter.class
            );
        return http.build();
    }

    // ── Mobile REST API chain (stateless JWT) ─────────────────────────────────

    @Bean
    @Order(2)
    public SecurityFilterChain mobileApiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/mobile/**")
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/mobile/auth/**").permitAll()
                // Jamais un compte en attente de choix de societe (defense en profondeur : le filtre JWT ne
                // l'authentifie deja pas ici).
                .anyRequest().access(authentifieHorsSelection())
            )
            .addFilterBefore(
                new JwtAuthenticationFilter(jwtTokenProvider, customUserDetailsService),
                UsernamePasswordAuthenticationFilter.class
            );
        return http.build();
    }

    /**
     * Authentifie ET pas en attente de choix de societe ({@code ROLE_SELECTION_SOCIETE}) : regle par defaut des
     * chaines API, pour qu'un compte en attente n'atteigne aucune route ouverte "a tout compte authentifie".
     */
    private static AuthorizationManager<RequestAuthorizationContext> authentifieHorsSelection() {
        return AuthorizationManagers.allOf(
                AuthenticatedAuthorizationManager.authenticated(),
                AuthorizationManagers.not(AuthorityAuthorizationManager.hasRole("SELECTION_SOCIETE")));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
            "http://localhost:4200",
            "http://localhost:4201",
            "https://app.logicielpourtous.com",
            "https://espace.logicielpourtous.com",
            // App mobile Capacitor (iOS/Android + dev local)
            "https://localhost",
            "capacitor://localhost",
            "ionic://localhost"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(customUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return new ProviderManager(provider);
    }

    // ── Web session chain ─────────────────────────────────────────────────────

    private static final Set<String> METHODES_SANS_EFFET = Set.of("GET", "HEAD", "TRACE", "OPTIONS");

    /**
     * Requetes de la chaine web qui DOIVENT porter un jeton CSRF : methode modifiante (POST, PUT, PATCH,
     * DELETE) sur la licence et la federation des serveurs, la gestion des ESN/tenants, le reglage de la
     * societe et le selecteur de societe (choix de la societe active). Chaque formulaire de ces ecrans doit etre ecrit avec {@code th:action} (Thymeleaf ajoute
     * alors le jeton) ou porter un champ {@code _csrf} ; tout appel JavaScript y enverrait le jeton en
     * en-tete. Pour proteger un autre ecran, ajouter son chemin ici une fois ses formulaires verifies.
     */
    private static RequestMatcher csrfCible() {
        PathPatternRequestMatcher.Builder chemins = PathPatternRequestMatcher.withDefaults();
        RequestMatcher ecransSensibles = new OrRequestMatcher(
                chemins.matcher("/intra/licence/**"),
                chemins.matcher("/intra/esns/**"),
                chemins.matcher("/intra/settings/company/**"),
                chemins.matcher("/intra/demandes-societe-connue/**"),
                chemins.matcher("/intra/societes/reprise/**"),
                chemins.matcher(SelectionSocieteFilter.SELECTEUR));
        RequestMatcher methodeModifiante = request -> !METHODES_SANS_EFFET.contains(request.getMethod());
        return new AndRequestMatcher(methodeModifiante, ecransSensibles);
    }

    @Bean
    @Order(3)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CSRF CIBLE : exige uniquement sur les ecrans sensibles listes dans csrfCible() ; le reste de
            // l'interface web est inchange pour l'instant (extension par lots, apres audit des formulaires).
            .csrf(csrf -> csrf.requireCsrfProtectionMatcher(csrfCible()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**", "/uploads/**").permitAll()
                .requestMatchers("/app", "/app/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .requestMatchers("/", "/register", "/register/confirm", "/register/email-sent", "/register/societe-connue","/register/verify", "/register/resend", "/login", "/public/**", "/error", "/access-denied").permitAll()
                .requestMatchers("/auth/session-token").permitAll()
                // Selecteur de societe : avant la regle generique /{slug} (permitAll), qui l'ouvrirait aux anonymes.
                .requestMatchers(SelectionSocieteFilter.SELECTEUR).authenticated()
                // Controle de sante (agent de deploiement bleu-vert) : seul /actuator/health est expose.
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/reset-password", "/reset-password/**").permitAll()
                .requestMatchers("/boutique", "/boutique/**", "/sitemap.xml", "/robots.txt").permitAll()
                .requestMatchers("/booking/**").permitAll()
                .requestMatchers("/blog", "/blog/**").permitAll()
                .requestMatchers("/chat", "/chat/**", "/ws/**").permitAll()
                .requestMatchers("/intra/pages/**", "/intra/menu/**").hasAnyRole("ADMIN", "USER", "COMMERCIAL", "TECHNICIEN", "MARKETING", "COMPTABLE")
                .requestMatchers("/{slug:[a-z0-9][a-z0-9\\-]*}").permitAll()
                .requestMatchers("/api/v1/shop/**").permitAll()
                .requestMatchers("/api/stripe/webhook/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/intra/**").hasAnyRole("ADMIN", "USER", "COMMERCIAL", "TECHNICIEN", "MARKETING", "COMPTABLE")
                .requestMatchers("/espace", "/espace/**").hasAnyRole("ADMIN", "CLIENT", "GESTIONNAIRE")
                .anyRequest().authenticated()
            )
            .addFilterBefore(captchaFilter, UsernamePasswordAuthenticationFilter.class)
            // Personne de plusieurs societes sans societe choisie : rien d'autre que le selecteur (voir le filtre).
            // Avant l'autorisation pour rediriger plutot que refuser, apres toutes les authentifications.
            .addFilterBefore(new SelectionSocieteFilter(), AuthorizationFilter.class)
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(successHandler)
                .failureHandler(authFailureHandler)
                .permitAll())
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .successHandler(successHandler)
                .userInfoEndpoint(ui -> ui
                    .userService(socialLoginUserService)
                    .oidcUserService(oidcSocialLoginUserService)
                )
            )
            .logout(logout -> logout.permitAll())
            .rememberMe(remember -> remember
                .key(CLE_REMEMBER_ME)
                .tokenValiditySeconds(86400 * 30)
                .userDetailsService(customUserDetailsService)
                .rememberMeParameter("remember-me")
            )
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint((request, response, authException) -> {
                    if ("htmx-request".equals(request.getHeader("HX-Request"))) {
                        response.setHeader("HX-Redirect", "/login");
                        response.setStatus(200);
                    } else {
                        response.sendRedirect("/login");
                    }
                })
            );
        return http.build();
    }

    @Bean
    public SpringSecurityDialect springSecurityDialect() {
        return new SpringSecurityDialect();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
