package pt.isep.bmc.issue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * BMC Issue service. Layers (ADR-009): {@code domain} ← {@code application} ←
 * {@code adapters.in} / {@code adapters.out}; {@code config} is the
 * composition root.
 */
@SpringBootApplication
public class IssueApplication {

    public static void main(String[] args) {
        SpringApplication.run(IssueApplication.class, args);
    }
}
