package com.logicielpourtous.site.features.user;

import org.springframework.stereotype.Component;

import com.logicielpourtous.site.features.identite.EmailsUtilises;

/** Adaptateur du port {@link EmailsUtilises} : un e-mail est utilise s'il existe un utilisateur (casse ignoree). */
@Component
class EmailsUtilisesParUtilisateurs implements EmailsUtilises {

    private final UserRepository users;

    EmailsUtilisesParUtilisateurs(UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean estUtilise(String emailNormalise) {
        return users.existsByEmailIgnoreCase(emailNormalise);
    }
}
