package pt.isep.bmc.dispatch.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pt.isep.bmc.dispatch.application.command.ApproveProposalService;
import pt.isep.bmc.dispatch.application.command.BookingService;
import pt.isep.bmc.dispatch.application.command.BookingWorker;
import pt.isep.bmc.dispatch.application.command.ChangeTermsService;
import pt.isep.bmc.dispatch.application.command.CreateInterventionService;
import pt.isep.bmc.dispatch.application.command.ExecutionService;
import pt.isep.bmc.dispatch.application.command.IssueClosureService;
import pt.isep.bmc.dispatch.application.command.ProjectionService;
import pt.isep.bmc.dispatch.application.command.PurgeExpiredIdempotencyKeysService;
import pt.isep.bmc.dispatch.application.command.ReplaceContractorService;
import pt.isep.bmc.dispatch.application.command.RespondToRequestService;
import pt.isep.bmc.dispatch.application.port.out.BookingRepository;
import pt.isep.bmc.dispatch.application.port.out.CalendarPort;
import pt.isep.bmc.dispatch.application.port.out.CompletionReportRepository;
import pt.isep.bmc.dispatch.application.port.out.ContractorCatalogue;
import pt.isep.bmc.dispatch.application.port.out.EventOutbox;
import pt.isep.bmc.dispatch.application.port.out.IdGenerator;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyStore;
import pt.isep.bmc.dispatch.application.port.out.InterventionReadModel;
import pt.isep.bmc.dispatch.application.port.out.InterventionRepository;
import pt.isep.bmc.dispatch.application.port.out.IssueProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.MembershipProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.application.query.EligibleContractorsQueryService;
import pt.isep.bmc.dispatch.application.query.InterventionQueryService;

/** Registers the use cases, built from their ports. */
@Configuration(proxyBeanMethods = false)
class UseCaseConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PurgeExpiredIdempotencyKeysService purgeExpiredIdempotencyKeysService(
            TransactionRunner transactions, IdempotencyStore idempotency, Clock clock) {
        return new PurgeExpiredIdempotencyKeysService(
                transactions, idempotency, clock, PurgeExpiredIdempotencyKeysService.DEFAULT_BATCH_SIZE);
    }

    @Bean
    ProjectionService projectionService(
            TransactionRunner transactions, IssueProjectionRepository issues, MembershipProjectionRepository memberships) {
        return new ProjectionService(transactions, issues, memberships);
    }

    @Bean
    EligibleContractorsQueryService eligibleContractorsQueryService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            ContractorCatalogue catalogue) {
        return new EligibleContractorsQueryService(transactions, issues, memberships, catalogue);
    }

    @Bean
    CreateInterventionService createInterventionService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            ContractorCatalogue catalogue,
            InterventionRepository interventions,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        return new CreateInterventionService(
                transactions, issues, memberships, catalogue, interventions, idempotency, ids, clock);
    }

    @Bean
    ReplaceContractorService replaceContractorService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            ContractorCatalogue catalogue,
            InterventionRepository interventions,
            IdempotencyStore idempotency,
            Clock clock) {
        return new ReplaceContractorService(
                transactions, issues, memberships, catalogue, interventions, idempotency, clock);
    }

    @Bean
    ApproveProposalService approveProposalService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            ContractorCatalogue catalogue,
            InterventionRepository interventions,
            EventOutbox outbox,
            BookingRepository bookings,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        return new ApproveProposalService(
                transactions, issues, memberships, catalogue, interventions, outbox, bookings, idempotency, ids, clock);
    }

    @Bean
    RespondToRequestService respondToRequestService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            ContractorCatalogue catalogue,
            InterventionRepository interventions,
            EventOutbox outbox,
            BookingRepository bookings,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        return new RespondToRequestService(
                transactions, issues, catalogue, interventions, outbox, bookings, idempotency, ids, clock);
    }

    @Bean
    ChangeTermsService changeTermsService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            ContractorCatalogue catalogue,
            InterventionRepository interventions,
            EventOutbox outbox,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        return new ChangeTermsService(transactions, issues, memberships, catalogue, interventions, outbox, idempotency,
                ids, clock);
    }

    @Bean
    BookingService bookingService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            ContractorCatalogue catalogue,
            InterventionRepository interventions,
            BookingRepository bookings,
            EventOutbox outbox,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        return new BookingService(
                transactions, issues, memberships, catalogue, interventions, bookings, outbox, idempotency, ids, clock);
    }

    @Bean
    BookingWorker bookingWorker(
            TransactionRunner transactions,
            BookingRepository bookings,
            InterventionRepository interventions,
            CalendarPort calendar,
            EventOutbox outbox,
            IdGenerator ids,
            Clock clock) {
        return new BookingWorker(transactions, bookings, interventions, calendar, outbox, ids, clock);
    }

    @Bean
    ExecutionService executionService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships,
            ContractorCatalogue catalogue,
            InterventionRepository interventions,
            CompletionReportRepository completions,
            EventOutbox outbox,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        return new ExecutionService(transactions, issues, memberships, catalogue, interventions, completions, outbox,
                idempotency, ids, clock);
    }

    @Bean
    IssueClosureService issueClosureService(
            TransactionRunner transactions,
            IssueProjectionRepository issues,
            InterventionRepository interventions,
            BookingRepository bookings,
            EventOutbox outbox,
            IdGenerator ids,
            Clock clock) {
        return new IssueClosureService(transactions, issues, interventions, bookings, outbox, ids, clock);
    }

    @Bean
    InterventionQueryService interventionQueryService(
            TransactionRunner transactions,
            InterventionReadModel readModel,
            IssueProjectionRepository issues,
            MembershipProjectionRepository memberships) {
        return new InterventionQueryService(transactions, readModel, issues, memberships);
    }
}
