package pt.isep.bmc.issue.application;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import pt.isep.bmc.issue.application.port.out.BuildingRepository;
import pt.isep.bmc.issue.application.port.out.DuplicateIdempotencyKeyException;
import pt.isep.bmc.issue.application.port.out.EventOutbox;
import pt.isep.bmc.issue.application.port.out.IdGenerator;
import pt.isep.bmc.issue.application.port.out.IdempotencyRecord;
import pt.isep.bmc.issue.application.port.out.IdempotencyScope;
import pt.isep.bmc.issue.application.port.out.IdempotencyStore;
import pt.isep.bmc.issue.application.port.out.ImpactRepository;
import pt.isep.bmc.issue.application.port.out.IssueRepository;
import pt.isep.bmc.issue.application.port.out.MembershipRepository;
import pt.isep.bmc.issue.application.port.out.OutboxEvent;
import pt.isep.bmc.issue.application.port.out.ReportRepository;
import pt.isep.bmc.issue.application.port.out.SubscriptionRepository;
import pt.isep.bmc.issue.application.port.out.SummaryRepository;
import pt.isep.bmc.issue.application.port.out.TransactionRunner;
import pt.isep.bmc.issue.application.port.out.TriageDecisionRepository;
import pt.isep.bmc.issue.application.port.out.TriageSuggestionRepository;
import pt.isep.bmc.issue.domain.building.Area;
import pt.isep.bmc.issue.domain.building.AreaId;
import pt.isep.bmc.issue.domain.building.BuildingId;
import pt.isep.bmc.issue.domain.issue.Issue;
import pt.isep.bmc.issue.domain.issue.IssueHistoryEntry;
import pt.isep.bmc.issue.domain.issue.IssueId;
import pt.isep.bmc.issue.domain.issue.IssueSummary;
import pt.isep.bmc.issue.domain.issue.TriageDecision;
import pt.isep.bmc.issue.domain.issue.TriageSuggestion;
import pt.isep.bmc.issue.domain.membership.BuildingAccess;
import pt.isep.bmc.issue.domain.membership.Membership;
import pt.isep.bmc.issue.domain.membership.UserId;
import pt.isep.bmc.issue.domain.report.Report;
import pt.isep.bmc.issue.domain.report.ReportId;

/**
 * In-memory fakes of every output port. The transaction runner undoes the
 * writes of a transaction whose work throws, like a real rollback.
 */
public final class InMemoryPorts implements TransactionRunner, MembershipRepository, BuildingRepository,
        IssueRepository, ReportRepository, TriageDecisionRepository, TriageSuggestionRepository, EventOutbox,
        IdempotencyStore, IdGenerator {

    public final List<Membership> memberships = new ArrayList<>();
    public final Map<AreaId, Area> areas = new HashMap<>();
    public final List<Issue> issues = new ArrayList<>();
    public final List<IssueHistoryEntry> history = new ArrayList<>();
    public final Map<ReportId, Report> reports = new HashMap<>();
    public final List<OutboxEvent> outbox = new ArrayList<>();
    public final Map<IdempotencyScope, IdempotencyRecord> keys = new HashMap<>();
    public final List<TriageDecision> decisions = new ArrayList<>();
    public final List<TriageSuggestion> suggestions = new ArrayList<>();
    public final List<IssueSummary> summaries = new ArrayList<>();
    public final List<Mark> confirmations = new ArrayList<>();
    public final List<Mark> subscriptions = new ArrayList<>();
    /** When set, the next record() acts as if a concurrent transaction committed this record first. */
    public Runnable beforeNextRecord;
    public RuntimeException failNextTransaction;
    /** When n > 0, the n-th append from now fails. */
    public int failAppendNumber;

    /** One undo journal per open transaction; a nested transaction that commits is not undone by its parent. */
    private final Deque<List<Runnable>> journals = new ArrayDeque<>();

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        if (failNextTransaction != null) {
            RuntimeException e = failNextTransaction;
            failNextTransaction = null;
            throw e;
        }
        journals.push(new ArrayList<>());
        try {
            T result = work.get();
            journals.pop();
            return result;
        } catch (RuntimeException e) {
            List<Runnable> undo = journals.pop();
            for (int i = undo.size() - 1; i >= 0; i--) {
                undo.get(i).run();
            }
            throw e;
        }
    }

    private void journal(Runnable undo) {
        if (!journals.isEmpty()) {
            journals.peek().add(undo);
        }
    }

    @Override
    public <T> T readOnly(Supplier<T> work) {
        return work.get();
    }

    @Override
    public List<Membership> findAll() {
        return List.copyOf(memberships);
    }

    @Override
    public List<Membership> findByUser(UserId userId) {
        return memberships.stream().filter(m -> m.userId().equals(userId)).toList();
    }

    @Override
    public Optional<Area> findArea(AreaId id) {
        return Optional.ofNullable(areas.get(id));
    }

    @Override
    public List<Area> findAreas(BuildingId buildingId) {
        return areas.values().stream().filter(a -> a.belongsTo(buildingId)).toList();
    }

    @Override
    public void add(Issue issue) {
        issues.add(issue);
        journal(() -> issues.remove(issue));
    }

    @Override
    public Optional<Issue> find(IssueId id) {
        return findForUpdate(id);
    }

    @Override
    public Optional<Issue> findForUpdate(IssueId id) {
        return issues.stream().filter(issue -> issue.id().equals(id)).findFirst();
    }

    @Override
    public void update(Issue issue) {
        Issue previous = findForUpdate(issue.id()).orElseThrow();
        int index = issues.indexOf(previous);
        issues.set(index, issue);
        journal(() -> issues.set(issues.indexOf(issue), previous));
    }

    @Override
    public int nextHistorySequence(IssueId id) {
        return (int) history.stream().filter(entry -> entry.issueId().equals(id)).count() + 1;
    }

    @Override
    public void add(TriageDecision decision) {
        decisions.add(decision);
        journal(() -> decisions.remove(decision));
    }

    @Override
    public Optional<TriageDecision> find(IssueId issueId, long resultingVersion) {
        return decisions.stream()
                .filter(d -> d.issueId().equals(issueId) && d.resultingVersion() == resultingVersion)
                .findFirst();
    }

    @Override
    public boolean existsByEventId(UUID eventId) {
        return suggestions.stream().anyMatch(suggestion -> suggestion.eventId().equals(eventId));
    }

    @Override
    public void add(TriageSuggestion suggestion) {
        suggestions.add(suggestion);
        journal(() -> suggestions.remove(suggestion));
    }

    @Override
    public boolean existsByIssueAndAuthor(IssueId issueId, UserId author) {
        return reports.values().stream()
                .anyMatch(report -> report.issueId().equals(issueId) && report.authorUserId().equals(author));
    }

    @Override
    public void appendHistory(IssueHistoryEntry entry) {
        history.add(entry);
        journal(() -> history.remove(entry));
    }

    @Override
    public void add(Report report) {
        reports.put(report.id(), report);
        journal(() -> reports.remove(report.id()));
    }

    @Override
    public Optional<Report> findById(ReportId id) {
        return Optional.ofNullable(reports.get(id));
    }

    @Override
    public boolean appendIfAbsent(OutboxEvent event) {
        if (outbox.stream().anyMatch(existing -> existing.eventId().equals(event.eventId()))) {
            return false;
        }
        append(event);
        return true;
    }

    @Override
    public void append(OutboxEvent event) {
        if (failAppendNumber > 0 && --failAppendNumber == 0) {
            throw new IllegalStateException("simulated outbox failure");
        }
        outbox.add(event);
        journal(() -> outbox.remove(event));
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyScope scope) {
        return Optional.ofNullable(keys.get(scope));
    }

    @Override
    public void record(IdempotencyRecord record) {
        if (beforeNextRecord != null) {
            Runnable race = beforeNextRecord;
            beforeNextRecord = null;
            race.run();
        }
        if (keys.containsKey(record.scope())) {
            throw new DuplicateIdempotencyKeyException();
        }
        keys.put(record.scope(), record);
        journal(() -> keys.remove(record.scope()));
    }

    @Override
    public void deleteIfExpired(IdempotencyScope scope, Instant now) {
        IdempotencyRecord record = keys.get(scope);
        if (record != null && record.isExpiredAt(now)) {
            keys.remove(scope);
            journal(() -> keys.put(scope, record));
        }
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        List<IdempotencyScope> expired = keys.values().stream()
                .filter(r -> r.isExpiredAt(now))
                .sorted(Comparator.comparing(IdempotencyRecord::expiresAt))
                .limit(limit)
                .map(IdempotencyRecord::scope)
                .toList();
        expired.forEach(keys::remove);
        return expired.size();
    }

    @Override
    public UUID newId() {
        return UUID.randomUUID();
    }

    /** One user's impact confirmation or subscription for an issue. */
    public record Mark(IssueId issueId, UserId userId) {
    }

    /**
     * Summaries, impact confirmations and subscriptions have methods whose
     * signatures collide with other ports, so each is its own view of this
     * object, sharing its rollback.
     */
    public SummaryRepository summaryRepository() {
        return new SummaryRepository() {
            @Override
            public void add(IssueSummary summary) {
                summaries.add(summary);
                journal(() -> summaries.remove(summary));
            }

            @Override
            public Optional<IssueSummary> find(IssueId issueId, long resultingVersion) {
                return summaries.stream()
                        .filter(s -> s.issueId().equals(issueId) && s.resultingVersion() == resultingVersion)
                        .findFirst();
            }
        };
    }

    public ImpactRepository impactRepository() {
        return new ImpactRepository() {
            @Override
            public boolean exists(IssueId issueId, UserId userId) {
                return confirmations.contains(new Mark(issueId, userId));
            }

            @Override
            public void add(IssueId issueId, UserId userId, Instant confirmedAt) {
                addMark(confirmations, new Mark(issueId, userId));
            }

            @Override
            public void remove(IssueId issueId, UserId userId) {
                removeMark(confirmations, new Mark(issueId, userId));
            }

            /** As in the SQL: only confirmations of people who are active residents of the issue's building. */
            @Override
            public long count(IssueId issueId, Instant now) {
                BuildingId building = InMemoryPorts.this.find(issueId).orElseThrow().buildingId();
                return confirmations.stream()
                        .filter(mark -> mark.issueId().equals(issueId))
                        .filter(mark -> BuildingAccess.of(mark.userId(), findByUser(mark.userId()), now)
                                .isResidentOf(building))
                        .count();
            }
        };
    }

    public SubscriptionRepository subscriptionRepository() {
        return new SubscriptionRepository() {
            @Override
            public boolean exists(IssueId issueId, UserId userId) {
                return subscriptions.contains(new Mark(issueId, userId));
            }

            @Override
            public void add(IssueId issueId, UserId userId, Instant subscribedAt) {
                addMark(subscriptions, new Mark(issueId, userId));
            }

            @Override
            public void remove(IssueId issueId, UserId userId) {
                removeMark(subscriptions, new Mark(issueId, userId));
            }
        };
    }

    private void addMark(List<Mark> marks, Mark mark) {
        if (marks.contains(mark)) {
            throw new IllegalStateException("one row per user and issue");
        }
        marks.add(mark);
        journal(() -> marks.remove(mark));
    }

    private void removeMark(List<Mark> marks, Mark mark) {
        if (marks.remove(mark)) {
            journal(() -> marks.add(mark));
        }
    }
}
