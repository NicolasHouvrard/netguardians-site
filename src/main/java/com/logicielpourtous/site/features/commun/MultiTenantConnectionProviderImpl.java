package com.logicielpourtous.site.features.commun;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component
public class MultiTenantConnectionProviderImpl implements MultiTenantConnectionProvider<Object> {

    private final DataSource dataSource;

    public MultiTenantConnectionProviderImpl(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

   @Override
public Connection getConnection(Object tenantIdentifier) throws SQLException {
    final Connection connection = getAnyConnection();
    try {
        String tenantId = (tenantIdentifier != null) ? tenantIdentifier.toString() : "public";
        
        // Validation anti-injection SQL
        if (!tenantId.matches("^[a-zA-Z0-9_]+$")) {
            throw new SQLException("TenantId invalide : " + tenantId);
        }
        
        try (var stmt = connection.createStatement()) {
            stmt.execute("SET search_path TO \"" + tenantId + "\", public");
        }
    } catch (SQLException e) {
        connection.close();
        throw new RuntimeException("Erreur switch schéma Postgres : " + tenantIdentifier, e);
    }
    return connection;
}

    @Override
public void releaseConnection(Object tenantIdentifier, Connection connection) throws SQLException {
    try (var stmt = connection.createStatement()) {
        stmt.execute("SET search_path TO public");
    } catch (SQLException e) {
        throw new RuntimeException("Erreur lors de la remise à zéro du search_path", e);
    } finally {
        connection.close();
    }
}

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        return null;
    }
}