package pt.isep.bmc.calendarsim.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Without {@code SIM_CONTROL_ENABLED=true} nobody can script failures or lose bookings. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ControlDisabledTest {

    @LocalServerPort
    int port;

    @Test
    void theControlApiDoesNotExistByDefault() throws Exception {
        SimClient sim = new SimClient(port);

        assertThat(sim.script("create", List.of(Map.of("type", "conflict"))).status()).isEqualTo(404);
        assertThat(sim.control("DELETE", "/bookings").status()).isEqualTo(404);
        assertThat(sim.control("GET", "/bookings").status()).isEqualTo(404);
    }
}
