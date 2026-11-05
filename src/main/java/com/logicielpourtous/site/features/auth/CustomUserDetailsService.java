package com.logicielpourtous.site.features.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true) // Important pour charger les rôles Lazy si besoin
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmailWithRoles(email)
        .orElseThrow(() -> new UsernameNotFoundException("Utilisateur non trouvé : " + email));
        return new CustomUserDetails(user); 
   
            }
}