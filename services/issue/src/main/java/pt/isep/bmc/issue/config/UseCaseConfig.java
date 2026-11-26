package pt.isep.bmc.issue.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pt.isep.bmc.issue.adapters.in.startup.PendingCriticalChecksRunner;
import pt.isep.bmc.issue.application.command.CompletePendingCriticalChecksService;
import pt.isep.bmc.issue.application.command.DecideTriageService;
import pt.isep.bmc.issue.application.command.ImpactService;
import pt.isep.bmc.issue.application.command.MembershipAnnouncementService;
import pt.isep.bmc.issue.application.command.PublishSummaryService;
import pt.isep.bmc.issue.application.command.PurgeExpiredIdempotencyKeysService;
import pt.isep.bmc.issue.application.command.RecordTriageSuggestionService;
import pt.isep.bmc.issue.application.command.SubmitReportService;
import pt.isep.bmc.issue.application.command.SubscriptionService;
import pt.isep.bmc.issue.application.port.in.CompletePendingCriticalChecksUseCase;
import pt.isep.bmc.issue.application.port.out.BuildingRepository;
import pt.isep.bmc.issue.application.port.out.CriticalCheckLog;
import pt.isep.bmc.issue.application.port.out.CriticalSignalRepository;
import pt.isep.bmc.issue.application.port.out.EventOutbox;
import pt.isep.bmc.issue.application.port.out.IdGenerator;
import pt.isep.bmc.issue.application.port.out.IdempotencyStore;
import pt.isep.bmc.issue.application.port.out.ImpactRepository;
import pt.isep.bmc.issue.application.port.out.IssueReadModel;
import pt.isep.bmc.issue.application.port.out.IssueRepository;
import pt.isep.bmc.issue.application.port.out.MembershipRepository;
import pt.isep.bmc.issue.application.port.out.ProfileReadModel;
import pt.isep.bmc.issue.application.port.out.ReportReadModel;
import pt.isep.bmc.issue.application.port.out.ReportRepository;
import pt.isep.bmc.issue.application.port.out.SubscriptionRepository;
import pt.isep.bmc.issue.application.port.out.SummaryRepository;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.application.port.out.TriageDecisionRepository;
import pt.isep.bmc.issue.application.port.out.TriageSuggestionRepository;
import pt.isep.bmc.issue.application.query.BuildingAreasQueryService;
import pt.isep.bmc.issue.application.query.IssueQueryService;
import pt.isep.bmc.issue.application.query.ProfileQueryService;
import pt.isep.bmc.issue.application.query.ReportQueryService;
import pt.isep.bmc.issue.domain.issue.CriticalAlertsV1;
import pt.isep.bmc.issue.domain.issue.CriticalRules;

/** Registers the use cases, built from their ports. */
@Configuration(proxyBeanMethods = false)
class UseCaseConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Issue's own critical rule set (technical design 5, step 1). One bean, so a test can replace it. */
    @Bean
    CriticalRules criticalRules() {
        return new CriticalAlertsV1();
    }

    @Bean
    SubmitReportService submitReportService(
            TransactionRunner transactions,
            MembershipRepository memberships,
            BuildingRepository buildings,
            IssueRepository issues,
            ReportRepository reports,
            CriticalSignalRepository criticalSignals,
            CriticalRules criticalRules,
            CriticalCheckLog criticalCheckLog,
            EventOutbox outbox,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        return new SubmitReportService(
                transactions, memberships, buildings, issues, reports, criticalSignals, criticalRules,
                criticalCheckLog, outbox, idempotency, ids, clock);
    }

    @Bean
    CompletePendingCriticalChecksService completePendingCriticalChecksService(
            TransactionRunner transactions,
            IssueRepository issues,
            ReportRepository reports,
            CriticalSignalRepository criticalSignals,
            CriticalRules criticalRules,
            CriticalCheckLog criticalCheckLog,
            Clock clock) {
        return new CompletePendingCriticalChecksService(
                transactions, issues, reports, criticalSignals, criticalRules, criticalCheckLog, clock,
                CompletePendingCriticalChecksService.DEFAULT_BATCH_SIZE);
    }

    /** At every start: complete the critical checks that are still pending. */
    @Bean
    PendingCriticalChecksRunner pendingCriticalChecksRunner(CompletePendingCriticalChecksUseCase checks) {
        return new PendingCriticalChecksRunner(checks);
    }

    @Bean
    PurgeExpiredIdempotencyKeysService purgeExpiredIdempotencyKeysService(
            TransactionRunner transactions, IdempotencyStore idempotency, Clock clock) {
        return new PurgeExpiredIdempotencyKeysService(
                transactions, idempotency, clock, PurgeExpiredIdempotencyKeysService.DEFAULT_BATCH_SIZE);
    }

    @Bean
    DecideTriageService decideTriageService(
            TransactionRunner transactions,
            MembershipRepository memberships,
            BuildingRepository buildings,
            IssueRepository issues,
            ReportRepository reports,
            TriageDecisionRepository decisions,
            EventOutbox outbox,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        return new DecideTriageService(
                transactions, memberships, buildings, issues, reports, decisions, outbox, idempotency, ids, clock);
    }

    @Bean
    RecordTriageSuggestionService recordTriageSuggestionService(
            TransactionRunner transactions,
            BuildingRepository buildings,
            IssueRepository issues,
            TriageSuggestionRepository suggestions,
            EventOutbox outbox,
            IdGenerator ids,
            Clock clock) {
        return new RecordTriageSuggestionService(transactions, buildings, issues, suggestions, outbox, ids, clock);
    }

    @Bean
    PublishSummaryService publishSummaryService(
            TransactionRunner transactions,
            MembershipRepository memberships,
            BuildingRepository buildings,
            IssueRepository issues,
            ReportRepository reports,
            SummaryRepository summaries,
            EventOutbox outbox,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Clock clock) {
        return new PublishSummaryService(
                transactions, memberships, buildings, issues, reports, summaries, outbox, idempotency, ids, clock);
    }

    @Bean
    ImpactService impactService(
            TransactionRunner transactions,
            MembershipRepository memberships,
            IssueRepository issues,
            ReportRepository reports,
            ImpactRepository impacts,
            Clock clock) {
        return new ImpactService(transactions, memberships, issues, reports, impacts, clock);
    }

    @Bean
    SubscriptionService subscriptionService(
            TransactionRunner transactions,
            MembershipRepository memberships,
            IssueRepository issues,
            ReportRepository reports,
            SubscriptionRepository subscriptions,
            Clock clock) {
        return new SubscriptionService(transactions, memberships, issues, reports, subscriptions, clock);
    }

    @Bean
    IssueQueryService issueQueryService(
            TransactionRunner transactions,
            IssueReadModel readModel,
            MembershipRepository memberships,
            ReportRepository reports,
            Clock clock) {
        return new IssueQueryService(transactions, readModel, memberships, reports, clock);
    }

    @Bean
    MembershipAnnouncementService membershipAnnouncementService(
            TransactionRunner transactions, MembershipRepository memberships, EventOutbox outbox, Clock clock) {
        return new MembershipAnnouncementService(transactions, memberships, outbox, clock);
    }

    @Bean
    ReportQueryService reportQueryService(
            TransactionRunner transactions, ReportReadModel readModel, MembershipRepository memberships, Clock clock) {
        return new ReportQueryService(transactions, readModel, memberships, clock);
    }

    @Bean
    ProfileQueryService profileQueryService(TransactionRunner transactions, ProfileReadModel readModel, Clock clock) {
        return new ProfileQueryService(transactions, readModel, clock);
    }

    @Bean
    BuildingAreasQueryService buildingAreasQueryService(
            TransactionRunner transactions, BuildingRepository buildings, MembershipRepository memberships, Clock clock) {
        return new BuildingAreasQueryService(transactions, buildings, memberships, clock);
    }
}
