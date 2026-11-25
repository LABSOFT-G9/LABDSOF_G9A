package pt.isep.bmc.issue.adapters.in.startup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import pt.isep.bmc.issue.application.port.in.AnnounceMembershipsUseCase;

/**
 * Runs once at start, after the migrations, where the synthetic fixtures are
 * loaded ({@code BMC_SEED_SYNTHETIC=true}): the seeded memberships get their
 * {@code MembershipGranted} events, so Dispatch can authorise the synthetic
 * administrators (technical design 4.3). A failure is logged and the service
 * still starts; the next start tries again.
 */
public final class SeededMembershipAnnouncer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeededMembershipAnnouncer.class);

    private final AnnounceMembershipsUseCase announce;

    public SeededMembershipAnnouncer(AnnounceMembershipsUseCase announce) {
        this.announce = announce;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int written = announce.announce();
            log.info("Announced {} seeded membership(s) through the outbox", written);
        } catch (RuntimeException e) {
            log.warn("Seeded memberships were not announced: {}", e.getClass().getName());
        }
    }
}
