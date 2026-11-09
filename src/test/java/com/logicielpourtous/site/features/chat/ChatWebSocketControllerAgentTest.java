package com.logicielpourtous.site.features.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.User;

/**
 * Un compte en attente de choix de societe n'a pas de fiche client (customerId nul) : il ne doit pas pour
 * autant etre pris pour un agent interne qui s'attribue une conversation en attente.
 */
class ChatWebSocketControllerAgentTest {

    private ChatConversationRepository conversations;
    private ChatMessageRepository messages;
    private ChatWebSocketController controleur;
    private ChatConversation conv;

    @BeforeEach
    void preparer() {
        conversations = mock(ChatConversationRepository.class);
        messages = mock(ChatMessageRepository.class);
        when(messages.save(any())).thenAnswer(i -> i.getArgument(0));
        controleur = new ChatWebSocketController(conversations, messages,
                mock(SimpMessagingTemplate.class), mock(ChatBotService.class));
        conv = new ChatConversation();
        conv.setId(5L);
        conv.setStatus(ConversationStatus.PENDING);
        conv.setType(ConversationType.PUBLIC);
        when(conversations.findById(5L)).thenReturn(Optional.of(conv));
    }

    @Test
    void compteEnAttenteDeChoix_estUnVisiteur_etNePrendPasLaConversation() {
        envoyer(multiSocietes(null));

        assertThat(dernierMessage().getSenderType()).isEqualTo(SenderType.VISITOR);
        assertThat(conv.getStatus()).isEqualTo(ConversationStatus.PENDING);
        assertThat(conv.getAssignedUserId()).isNull();
    }

    @Test
    void compteInterneSansFiche_resteUnAgent() {
        envoyer(new CustomUserDetails(personne()));

        assertThat(dernierMessage().getSenderType()).isEqualTo(SenderType.USER);
        assertThat(conv.getStatus()).isEqualTo(ConversationStatus.OPEN);
        assertThat(conv.getAssignedUserId()).isEqualTo(1L);
    }

    private void envoyer(CustomUserDetails principal) {
        controleur.sendMessage(5L, new ChatWebSocketController.MessagePayload("bonjour"),
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private ChatMessage dernierMessage() {
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        Mockito.verify(messages).save(captor.capture());
        return captor.getValue();
    }

    private static User personne() {
        Tenant t = new Tenant();
        ReflectionTestUtils.setField(t, "id", 1L);
        t.setSchemaName("schema_t");
        User u = new User();
        ReflectionTestUtils.setField(u, "id", 1L);
        u.setEmail("agent@exemple.fr");
        u.setPassword("x");
        u.setFullName("Agent");
        u.setActive(true);
        u.setTenant(t);
        u.setRoles(Set.of(new Role("ADMIN", "Admin", "ESN")));
        return u;
    }

    private static CustomUserDetails multiSocietes(Long active) {
        User u = personne();
        return new CustomUserDetails(u, List.of(appartenance(10, u.getTenant()), appartenance(11, u.getTenant())), active);
    }

    private static Appartenance appartenance(long id, Tenant t) {
        Appartenance a = new Appartenance();
        a.setId(id);
        a.setUserId(1L);
        a.setTenant(t);
        a.setRoles(Set.of(new Role("ADMIN", "Admin", "ESN")));
        return a;
    }
}
