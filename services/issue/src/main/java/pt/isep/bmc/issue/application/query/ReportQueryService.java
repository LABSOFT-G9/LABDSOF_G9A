package pt.isep.bmc.issue.application.query;

import java.time.Clock;
import java.util.Optional;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.port.in.GetReportQuery;
import pt.isep.bmc.issue.application.port.in.ListReportsQuery;
import pt.isep.bmc.issue.application.port.out.MembershipRepository;
import pt.isep.bmc.issue.application.port.out.ReportReadModel;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.report.ReportId;

/**
 * Report queries. Every read checks the caller's memberships on the server;
 * anything outside the caller's scope is reported as not found.
 */
public final class ReportQueryService implements GetReportQuery, ListReportsQuery {

    private final TransactionRunner transactions;
    private final ReportReadModel readModel;
    private final MembershipRepository memberships;
    private final Clock clock;

    public ReportQueryService(
            TransactionRunner transactions, ReportReadModel readModel, MembershipRepository memberships, Clock clock) {
        this.transactions = transactions;
        this.readModel = readModel;
        this.memberships = memberships;
        this.clock = clock;
    }

    @Override
    public ReportDetails get(UserId actor, ReportId reportId) {
        return transactions.readOnly(() -> {
            ReportDetails details = readModel.findDetails(reportId).orElseThrow(NotFoundException::new);
            BuildingAccess access = BuildingAccess.of(actor, memberships.findByUser(actor), clock.instant());
            if (!access.canReadReport(details.authorUserId(), details.buildingId())) {
                throw new NotFoundException();
            }
            return details;
        });
    }

    @Override
    public ReportPage list(UserId actor, Optional<BuildingId> building, int page, int size) {
        return transactions.readOnly(() -> {
            BuildingAccess access = BuildingAccess.of(actor, memberships.findByUser(actor), clock.instant());
            return readModel.list(new ReportListCriteria(actor, access.administeredBuildings(), building, page, size));
        });
    }
}
