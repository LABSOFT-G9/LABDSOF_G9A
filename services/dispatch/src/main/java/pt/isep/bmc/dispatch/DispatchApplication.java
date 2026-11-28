package pt.isep.bmc.dispatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * BMC Dispatch service: contractors and interventions (technical design 2.2).
 * Layers (ADR-009): {@code domain} ← {@code application} ←
 * {@code adapters.in} / {@code adapters.out}; {@code config} is the
 * composition root.
 */
@SpringBootApplication
public class DispatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispatchApplication.class, args);
    }
}
