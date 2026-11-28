package pt.isep.bmc.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * BMC Notification service: the in-app inbox (technical design 2.3, ADR-007).
 * Layers (ADR-009): {@code domain} ← {@code application} ←
 * {@code adapters.in} / {@code adapters.out}; {@code config} is the
 * composition root.
 */
@SpringBootApplication
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
