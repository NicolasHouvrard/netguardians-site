package com.logicielpourtous.site.features.chat;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    List<ChatConversation> findByTenantOrderByLastMessageAtDesc(Tenant tenant);

    List<ChatConversation> findByTenantAndStatusOrderByLastMessageAtDesc(Tenant tenant, ConversationStatus status);

    List<ChatConversation> findByTenantAndStatusNotOrderByLastMessageAtDesc(Tenant tenant, ConversationStatus status);

    Optional<ChatConversation> findByVisitorEmailAndStatusNot(String email, ConversationStatus status);

    @Query("SELECT c FROM ChatConversation c WHERE c.tenant = :tenant AND c.customerId = :customerId ORDER BY c.lastMessageAt DESC")
    List<ChatConversation> findByTenantAndCustomerId(@Param("tenant") Tenant tenant, @Param("customerId") Long customerId);

    @Query("SELECT COUNT(c) FROM ChatConversation c WHERE c.tenant = :tenant AND c.status <> com.logicielpourtous.site.features.chat.ConversationStatus.CLOSED")
    long countOpenByTenant(@Param("tenant") Tenant tenant);

    long countByTenantAndStatus(Tenant tenant, ConversationStatus status);
}
