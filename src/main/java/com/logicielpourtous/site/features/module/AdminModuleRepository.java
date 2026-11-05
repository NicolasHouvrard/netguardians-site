
package  com.logicielpourtous.site.features.module;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional; // N'oublie pas l'import !


public interface AdminModuleRepository extends JpaRepository<AdminModule, Long> {
    // Récupère les modules triés par l'ordre que tu auras choisi
    List<AdminModule> findAllByOrderByDisplayOrderAsc();
    List<AdminModule> findAllByOrderByCategoryAscDisplayOrderAsc();
    List<AdminModule> findByActiveTrueOrderByCategoryAscDisplayOrderAsc();
    Optional<AdminModule> findFirstByTitle(String title);
    Long countByRoleRequired(String roleRequired);
    Optional<AdminModule> findFirstByUrl(String url);
}