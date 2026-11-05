package com.logicielpourtous.site;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthTest {

	@LocalServerPort
	private int port;

	@Test
	void healthEndpointRepondUp() {
		var reponse = RestClient.create().get()
				.uri("http://localhost:" + port + "/actuator/health")
				.retrieve().toEntity(String.class);
		assertThat(reponse.getStatusCode().value()).isEqualTo(200);
		assertThat(reponse.getBody()).contains("\"status\":\"UP\"");
	}
}
