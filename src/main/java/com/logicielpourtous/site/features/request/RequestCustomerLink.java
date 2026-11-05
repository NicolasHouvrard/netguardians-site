package com.logicielpourtous.site.features.request;

import org.springframework.stereotype.Component;

import com.logicielpourtous.site.features.customers.Customer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Rattache une {@link Request} a un client connu par son seul id, pour le module {@code sync} (qui ne
 * depend pas du module {@code customers}) : creation par la synchronisation d'une demande recue d'un
 * serveur secondaire, rattachee au client que represente ce serveur.
 */
@Component
public class RequestCustomerLink {

    @PersistenceContext
    private EntityManager entityManager;

    /** Reference (proxy, sans chargement) vers le client {@code customerId}. A appeler dans une transaction. */
    public void rattacher(Request request, Long customerId) {
        request.setCustomer(entityManager.getReference(Customer.class, customerId));
    }
}
