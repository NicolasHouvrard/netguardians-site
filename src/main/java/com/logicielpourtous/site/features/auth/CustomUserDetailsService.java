package com.logicielpourtous.site.features.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    private final AppartenanceRepository appartenanceRepository;

    public CustomUserDetailsService(UserRepository userRepository, AppartenanceRepository appartenanceRepository) {
        this.userRepository = userRepository;
        this.appartenanceRepository = appartenanceRepository;
    }

    @Override
    @Transactional(readOnly = true) // Important pour charger les rôles Lazy si besoin
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return charger(email, null);
    }

    /**
     * Charge le compte avec ses appartenances. L'appartenance active n'est retenue que si elle
     * appartient bien à cette personne ; sinon la personne reste en attente de choix.
     */
    @Transactional(readOnly = true)
    public UserDetails charger(String email, Long appartenanceId) throws UsernameNotFoundException {
        User user = userRepository.findByEmailWithRoles(email)
                .orElseThrow(() -> new UsernameNotFoundException("Utilisateur non trouvé : " + email));
        List<Appartenance> appartenances = appartenanceRepository.findByUserIdOrderById(user.getId());
        Long active = appartenanceId != null
                && appartenances.stream().anyMatch(a -> appartenanceId.equals(a.getId()))
                ? appartenanceId : null;
        return new CustomUserDetails(user, appartenances, active);
    }
}
