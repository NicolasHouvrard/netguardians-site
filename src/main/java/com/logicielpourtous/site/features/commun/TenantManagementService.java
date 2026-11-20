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

    private static final java.util.regex.Pattern NOM_DE_SCHEMA = java.util.regex.Pattern.compile("^p_[0-9a-f]{12}$");
    private static final java.security.SecureRandom ALEA = new java.security.SecureRandom();

    /** Nom de schema aleatoire au format impose : "p_" + 12 hexadecimaux. */
    public static String nouveauNomDeSchema() {
        byte[] octets = new byte[6];
        ALEA.nextBytes(octets);
        StringBuilder sb = new StringBuilder("p_");
        for (byte b : octets) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Cree le tenant et son schema dans la transaction en cours : tout est annule ensemble en cas d'echec.
     * Le nom de schema est valide avant toute ecriture ; c'est le seul fragment insere dans le SQL.
     */
    @Transactional
    public Tenant creerTenantTransactionnel(String raisonSociale, String schemaName) {
        if (schemaName == null || !NOM_DE_SCHEMA.matcher(schemaName).matches()) {
            throw new IllegalArgumentException("Nom de schema invalide");
        }
        Tenant tenant = new Tenant();
        tenant.setName(raisonSociale);
        tenant.setSchemaName(schemaName);
        evenements.publishEvent(new TenantEnCreationEvenement(tenant));
        Tenant enregistre = tenantRepository.save(tenant);

        Connection connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE SCHEMA " + schemaName);
            statement.executeUpdate("CREATE TABLE " + schemaName + ".contacts (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "first_name VARCHAR(255), " +
                    "last_name VARCHAR(255), " +
                    "email VARCHAR(255), " +
                    "user_id BIGINT UNIQUE" +
                    ")");
        } catch (SQLException e) {
            throw new IllegalStateException("Erreur lors de la creation du schema : " + e.getMessage(), e);
        } finally {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection, dataSource);
        }
        return enregistre;
    }

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
