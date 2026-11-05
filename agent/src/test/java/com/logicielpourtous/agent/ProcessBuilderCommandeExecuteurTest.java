package com.logicielpourtous.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ProcessBuilderCommandeExecuteurTest {

    @Test
    void processusQuiNeTerminePasEstTueAuDelaiMaxSansBloquer() throws Exception {
        Process processusBloque = mock(Process.class);
        when(processusBloque.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(processusBloque.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(processusBloque.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));

        var executeur = new ProcessBuilderCommandeExecuteur(Duration.ofMillis(50)) {
            @Override
            Process demarrer(List<String> commande) {
                return processusBloque;
            }
        };

        assertThatThrownBy(() -> executeur.executer(List.of("docker", "stop", "abc")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Delai depasse");
        verify(processusBloque).destroyForcibly();
    }
}
