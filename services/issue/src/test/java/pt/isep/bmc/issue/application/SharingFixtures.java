package pt.isep.bmc.issue.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import pt.isep.bmc.issue.application.command.PublishSummaryCommand;
import pt.isep.bmc.issue.application.command.PublishSummaryService;
import pt.isep.bmc.issue.application.idempotency.IdempotencyKey;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.AreaKind;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.CriticalFindings;
import pt.isep.bmc.issue.domain.issue.CriticalAlertsV1;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.membership.Membership;
import pt.isep.bmc.issue.domain.membership.MembershipId;
import pt.isep.bmc.issue.domain.membership.MembershipRole;
import pt.isep.bmc.issue.domain.membership.MembershipStatus;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.report.Report;
import pt.isep.bmc.issue.domain.report.ReportId;
import pt.isep.bmc.issue.domain.report.ReportSource;
import pt.isep.bmc.issue.domain.report.ReportText;

/**
 * The same small world for the sharing use cases (US-04): Building A with an
 * administrator, the reporter and a neighbour; Building B with one resident;
 * and one issue in Building A's garage, reported by the reporter.
 */
final class SharingFixtures {

    static final Instant NOW = Instant.parse("2026-11-24T10:00:00.123456Z");
    static final Instant NOW_MILLIS = Instant.parse("2026-11-24T10:00:00.123Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final UserId ADMIN = new UserId("admin-a");
    static final UserId OTHER_ADMIN = new UserId("admin-a2");
    static final UserId REPORTER = new UserId("resident-a1");
    static final UserId NEIGHBOUR = new UserId("resident-a2");
    static final UserId OUTSIDER = new UserId("resident-b1");
    static final BuildingId A = new BuildingId(UUID.randomUUID());
    static final BuildingId B = new BuildingId(UUID.randomUUID());
    static final Area GARAGE = new Area(new AreaId(UUID.randomUUID()), A, "Garage level -1", AreaKind.COMMON_AREA);
    static final String SUMMARY = "The garage door on level -1 does not close fully. A repair is being arranged.";

    final InMemoryPorts ports = new InMemoryPorts();
    final Issue issue;

    SharingFixtures() {
        ports.memberships.add(membership(ADMIN, A, MembershipRole.ADMINISTRATOR));
        ports.memberships.add(membership(OTHER_ADMIN, A, MembershipRole.ADMINISTRATOR));
        ports.memberships.add(membership(REPORTER, A, MembershipRole.RESIDENT));
        ports.memberships.add(membership(NEIGHBOUR, A, MembershipRole.RESIDENT));
        ports.memberships.add(membership(OUTSIDER, B, MembershipRole.RESIDENT));
        ports.areas.put(GARAGE.id(), GARAGE);
        issue = Issue.receive(new IssueId(UUID.randomUUID()), A, GARAGE.id(), NOW.minusSeconds(600), CriticalFindings.none(CriticalAlertsV1.VERSION));
        ports.issues.add(issue);
        ports.history.add(IssueHistoryEntry.reportReceived(issue.id(), ReportSource.RESIDENT, issue.createdAt()));
        ReportId reportId = new ReportId(UUID.randomUUID());
        ports.reports.put(reportId, Report.restore(reportId, issue.id(), A, GARAGE.id(), REPORTER,
                ReportText.restore("The garage door stays half open."), ReportSource.RESIDENT, issue.createdAt()));
    }

    static Membership membership(UserId user, BuildingId building, MembershipRole role) {
        return new Membership(new MembershipId(UUID.randomUUID()), user, building, role, MembershipStatus.ACTIVE,
                NOW.minusSeconds(3600), null, 1);
    }

    PublishSummaryService publication() {
        return new PublishSummaryService(ports, ports, ports, ports, ports, ports.summaryRepository(), ports, ports,
                ports, CLOCK);
    }

    PublishSummaryCommand publish(UserId actor, String key, long version, String summary) {
        return new PublishSummaryCommand(actor, new IdempotencyKey(key), issue.id(), version, summary, "c-7");
    }

    /** Publishes a summary, so the issue is shared with Building A at version 2. */
    void share() {
        publication().publish(publish(ADMIN, "share", 1, SUMMARY));
    }

    Issue stored() {
        return ports.issues.get(0);
    }

    void revoke(UserId user) {
        ports.memberships.replaceAll(m -> m.userId().equals(user)
                ? new Membership(m.id(), user, m.buildingId(), m.role(), MembershipStatus.REVOKED, m.validFrom(), null,
                        m.version() + 1)
                : m);
    }

    void restore(UserId user) {
        ports.memberships.replaceAll(m -> m.userId().equals(user)
                ? new Membership(m.id(), user, m.buildingId(), m.role(), MembershipStatus.ACTIVE, m.validFrom(), null,
                        m.version() + 1)
                : m);
    }

    /** Ends the user's memberships at {@code validUntil}. */
    void endValidity(UserId user, Instant validUntil) {
        ports.memberships.replaceAll(m -> m.userId().equals(user)
                ? new Membership(m.id(), user, m.buildingId(), m.role(), m.status(), m.validFrom(), validUntil,
                        m.version() + 1)
                : m);
    }
}
