package com.logicielpourtous.site.features.commun;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ServerRolePropertiesTest {

    @Autowired
    private ServerRoleProperties serverRoleProperties;

    @Test
    void defaultsToMasterWhenNotConfigured() {
        assertThat(serverRoleProperties.getRole()).isEqualTo(ServerRole.MASTER);
    }

    @Nested
    @TestPropertySource(properties = "app.role=not-a-real-role")
    class InvalidRoleTest {

        @Autowired
        private ServerRoleProperties serverRoleProperties;

        @Test
        void fallsBackToMasterWhenConfiguredValueIsInvalid() {
            assertThat(serverRoleProperties.getRole()).isEqualTo(ServerRole.MASTER);
        }
    }

    @Nested
    @TestPropertySource(properties = "app.role=partner")
    class LenientCaseRoleTest {

        @Autowired
        private ServerRoleProperties serverRoleProperties;

        @Test
        void isCaseInsensitiveLikeStandardSpringEnumBinding() {
            assertThat(serverRoleProperties.getRole()).isEqualTo(ServerRole.PARTNER);
        }
    }
}
