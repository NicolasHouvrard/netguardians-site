package com.logicielpourtous.site.features.commun;

import java.sql.Statement;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TenantManagementService {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private org.springframework.context.ApplicationEventPublisher evenements;

    @Transactional
    public void createNewTenant(String companyName, String schemaName) {
        // 1. Sauvegarde dans le catalogue global (public)
        Tenant tenant = new Tenant();
        tenant.setName(companyName);
        tenant.setSchemaName(schemaName);
        // Les autres modules peuvent completer le tenant avant son enregistrement (ex. serveur d'accueil).
        evenements.publishEvent(new TenantEnCreationEvenement(tenant));
        tenantRepository.save(tenant);

        // 2. Création du schéma et des tables
        try (Connection connection = dataSource.getConnection()) {
            Statement statement = connection.createStatement();
            
            // Création du schéma
            statement.executeUpdate("CREATE SCHEMA " + schemaName);
            
            // Création de la table contact propre à ce client
            // Plus tard, tu mettras ici tous tes CREATE TABLE métier
            statement.executeUpdate("CREATE TABLE " + schemaName + ".contacts (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "first_name VARCHAR(255), " +
                    "last_name VARCHAR(255), " +
                    "email VARCHAR(255), " +
                    "user_id BIGINT UNIQUE" +
                    ")");

            // On peut aussi ajouter un premier contact par défaut ici
            
        } catch (SQLException e) {
            throw new RuntimeException("Erreur lors de la création du schéma : " + e.getMessage());
        }
    }
}
