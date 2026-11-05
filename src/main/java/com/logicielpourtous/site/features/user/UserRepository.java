package com.logicielpourtous.site.features.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.logicielpourtous.site.features.role.Role;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.roles WHERE u.email = :email")
    Optional<User> findByEmailWithRoles(@Param("email") String email);

    Long countByRolesContaining(Role role);

    List<User> findByCustomerId(Long customerId);

    /** Utilisateurs ESN internes (pas liés à un customer) */
    List<User> findByCustomerIdIsNull();

    /** Utilisateurs ayant l'un des rôles indiqués */
    @Query("SELECT DISTINCT u FROM User u JOIN u.roles r WHERE r.name IN :roleNames")
    List<User> findByRoleNameIn(@Param("roleNames") java.util.List<String> roleNames);

    /** Utilisateurs portail (liés à un customer) */
    List<User> findByCustomerIdIsNotNull();

    /**
     * Utilisateurs internes : ceux qui n'ont aucun rôle, ou dont au moins un rôle est de type ESN.
     * Exclut les comptes purement portail (uniquement rôles CLIENT).
     */
    @Query("""
        SELECT DISTINCT u FROM User u LEFT JOIN u.roles r
        WHERE r IS NULL OR r.type = 'ESN'
        ORDER BY u.fullName
        """)
    List<User> findInternalUsers();

    /** Tous les utilisateurs d'un customer donné + les ESN internes */
    @Query("SELECT u FROM User u WHERE u.customerId IS NULL OR u.customerId = :customerId")
    List<User> findEsnAndCustomerUsers(@Param("customerId") Long customerId);

    Optional<User> findByResetToken(String resetToken);

    Optional<User> findByEmailVerificationToken(String emailVerificationToken);
}
