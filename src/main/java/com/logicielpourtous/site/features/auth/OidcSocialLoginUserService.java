package com.logicielpourtous.site.features.auth;

import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import com.logicielpourtous.site.features.commun.AiguillageMaitre;
import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class OidcSocialLoginUserService extends OidcUserService {

    static final String MESSAGE_REFUS = "Connexion impossible avec ce compte.";

    private final UserRepository userRepository;
    private final ServerRoleProperties serverRole;
    private final Optional<AiguillageMaitre> aiguillage;

    /** Sans aiguillage (comme avant) : tout compte inconnu est refuse. */
    public OidcSocialLoginUserService(UserRepository userRepository) {
        this(userRepository, new ServerRoleProperties(), Optional.empty());
    }

    @Autowired
    public OidcSocialLoginUserService(UserRepository userRepository, ServerRoleProperties serverRole,
                                      Optional<AiguillageMaitre> aiguillage) {
        this.userRepository = userRepository;
        this.serverRole = serverRole;
        this.aiguillage = aiguillage;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        return rattacher(chargerIdentite(request), request.getClientRegistration().getRegistrationId());
    }

    /** Appel reseau (jeton d'identite + userinfo) ; isole pour les tests. */
    protected OidcUser chargerIdentite(OidcUserRequest request) {
        return super.loadUser(request);
    }

    /**
     * Rattache l'identite OpenID Connect a un compte local EXISTANT, jamais de creation. Tous les refus
     * (e-mail absent ou non verifie, compte inconnu, inactif ou ambigu) ont le meme message generique,
     * qui ne dit rien sur l'existence du compte.
     */
    OidcUser rattacher(OidcUser oidcUser, String registrationId) {
        String email = oidcUser.getEmail();
        if (email == null || email.isBlank()) {
            throw refus();
        }
        // email_verified == true OBLIGATOIRE pour tous les fournisseurs (echec ferme : claim absent, faux ou
        // fournisseur inconnu = refus).
        if (!Boolean.TRUE.equals(oidcUser.getEmailVerified())) {
            throw refus();
        }

        String minuscules = email.trim().toLowerCase(Locale.ROOT);
        List<User> comptes = userRepository.findAllByEmailIgnoreCase(minuscules);
        if (comptes.isEmpty() && aiguillageApplicable(registrationId)) {
            conserverJeton(oidcUser);
            throw new AiguillageRequis();
        }
        if (comptes.size() != 1 || !comptes.get(0).isActive()) {
            throw refus();
        }

        return new CustomOidcUser(oidcUser, comptes.get(0), minuscules);
    }

    /** Compte inconnu apres Authelia, sur un serveur secondaire pouvant interroger le Maitre (jamais le Maitre). */
    private boolean aiguillageApplicable(String registrationId) {
        return "authelia".equals(registrationId) && serverRole.getRole() != ServerRole.MASTER && aiguillage.isPresent();
    }

    /** Garde le jeton d'identite (secret, jamais journalise) en session pour l'aiguillage ; lu une seule fois. */
    private static void conserverJeton(OidcUser oidcUser) {
        if (oidcUser.getIdToken() == null || RequestContextHolder.getRequestAttributes() == null) {
            throw refus();
        }
        RequestAttributes session = RequestContextHolder.currentRequestAttributes();
        session.setAttribute(AiguillageRequis.ATTRIBUT_JETON, oidcUser.getIdToken().getTokenValue(),
                RequestAttributes.SCOPE_SESSION);
        session.setAttribute(AiguillageRequis.ATTRIBUT_JETON_INSTANT, System.currentTimeMillis(),
                RequestAttributes.SCOPE_SESSION);
    }

    private static OAuth2AuthenticationException refus() {
        return new OAuth2AuthenticationException(new OAuth2Error("connexion_refusee"), MESSAGE_REFUS);
    }
}
