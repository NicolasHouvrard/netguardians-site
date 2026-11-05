package com.logicielpourtous.site.features.mail;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MailAccountRepository extends JpaRepository<MailAccount, Long> {
    List<MailAccount> findByActiveTrueOrderByNameAsc();
    List<MailAccount> findAllByOrderByNameAsc();

    /** Calendrier personnel d'un utilisateur (Google ou Microsoft, token valide). */
    Optional<MailAccount> findFirstByUserIdAndAuthTypeInAndAccessTokenIsNotNull(
            Long userId, List<MailAccount.AuthType> authTypes);
}
