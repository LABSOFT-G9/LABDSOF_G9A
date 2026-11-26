package pt.isep.bmc.dispatch.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import pt.isep.bmc.dispatch.application.port.out.ContractorCatalogue;
import pt.isep.bmc.dispatch.application.port.out.DuplicateIdempotencyKeyException;
import pt.isep.bmc.dispatch.application.port.out.DuplicateProjectionRowException;
import pt.isep.bmc.dispatch.application.port.out.EventOutbox;
import pt.isep.bmc.dispatch.application.port.out.IdGenerator;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyRecord;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyScope;
import pt.isep.bmc.dispatch.application.port.out.IdempotencyStore;
import pt.isep.bmc.dispatch.application.port.out.InterventionRepository;
import pt.isep.bmc.dispatch.application.port.out.IssueProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.MembershipProjectionRepository;
import pt.isep.bmc.dispatch.application.port.out.OutboxEvent;
import pt.isep.bmc.dispatch.application.port.out.TransactionConflictException;
import pt.isep.bmc.dispatch.application.port.out.TransactionRunner;
import pt.isep.bmc.dispatch.domain.contractor.Contractor;
import pt.isep.bmc.dispatch.domain.contractor.ContractorId;
import pt.isep.bmc.dispatch.domain.intervention.ContractorResponse;
import pt.isep.bmc.dispatch.domain.intervention.Intervention;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.intervention.ProposalVersion;
import pt.isep.bmc.dispatch.domain.projection.MembershipId;
import pt.isep.bmc.dispatch.domain.projection.ProjectedIssue;
import pt.isep.bmc.dispatch.domain.projection.ProjectedMembership;
import pt.isep.bmc.dispatch.domain.shared.BuildingId;
import pt.isep.bmc.dispatch.domain.shared.IssueId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * In-memory fakes of Dispatch's command ports, as in Issue's application
 * tests. The transaction runner undoes the writes of a transaction whose work
 * throws, like a real rollback. Locks are not modelled: the integration tests
 * cover them against MySQL.
 */
public final class InMemoryPorts implements TransactionRunner, IssueProjectionRepository,
        MembershipProjectionRepository, ContractorCatalogue, InterventionRepository, EventOutbox, IdempotencyStore,
        IdGenerator {

    public final Map<IssueId, ProjectedIssue> issues = new HashMap<>();
    public final Map<MembershipId, ProjectedMembership> memberships = new HashMap<>();
    public final Map<ContractorId, Contractor> contractors = new HashMap<>();
    public final Map<InterventionId, Intervention> interventions = new HashMap<>();
    public final List<ProposalVersion> proposals = new ArrayList<>();
    public final List<ContractorResponse> responses = new ArrayList<>();
    public final List<OutboxEvent> outbox = new ArrayList<>();
    public final Map<IdempotencyScope, IdempotencyRecord> keys = new HashMap<>();
    /** When set, the next record() acts as if a concurrent transaction committed this record first. */
    public Runnable beforeNextRecord;
    /** When set, the next projection insert acts as if a concurrent transaction inserted the row first. */
    public Runnable beforeNextInsert;
    /** The next n transactions are rolled back by a concurrent one, as an InnoDB deadlock victim is. */
    public int conflictingTransactions;
    public int transactionsStarted;

    private final Deque<List<Runnable>> journals = new ArrayDeque<>();

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        transactionsStarted++;
        if (conflictingTransactions > 0) {
            conflictingTransactions--;
            throw new TransactionConflictException(new IllegalStateException("simulated deadlock"));
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

    @Override
    public <T> T readOnly(Supplier<T> work) {
        return work.get();
    }

    private void journal(Runnable undo) {
        if (!journals.isEmpty()) {
            journals.peek().add(undo);
        }
    }

    private <K, V> void put(Map<K, V> map, K key, V value) {
        V previous = map.put(key, value);
        journal(() -> {
            if (previous == null) {
                map.remove(key);
            } else {
                map.put(key, previous);
            }
        });
    }

    private void raceBeforeInsert() {
        if (beforeNextInsert != null) {
            Runnable race = beforeNextInsert;
            beforeNextInsert = null;
            race.run();
        }
    }

    // Issue projection

    @Override
    public Optional<ProjectedIssue> find(IssueId id) {
        return Optional.ofNullable(issues.get(id));
    }

    @Override
    public Optional<ProjectedIssue> findForUpdate(IssueId id) {
        return find(id);
    }

    @Override
    public void insert(ProjectedIssue issue) {
        raceBeforeInsert();
        if (issues.containsKey(issue.issueId())) {
            throw new DuplicateProjectionRowException();
        }
        put(issues, issue.issueId(), issue);
    }

    @Override
    public void update(ProjectedIssue issue) {
        put(issues, issue.issueId(), issue);
    }

    // Membership projection

    @Override
    public List<ProjectedMembership> findByUser(UserId userId) {
        return memberships.values().stream().filter(m -> m.userId().equals(userId)).toList();
    }

    @Override
    public Optional<ProjectedMembership> findForUpdate(MembershipId id) {
        return Optional.ofNullable(memberships.get(id));
    }

    @Override
    public void insert(ProjectedMembership membership) {
        raceBeforeInsert();
        if (memberships.containsKey(membership.membershipId())) {
            throw new DuplicateProjectionRowException();
        }
        put(memberships, membership.membershipId(), membership);
    }

    @Override
    public void update(ProjectedMembership membership) {
        put(memberships, membership.membershipId(), membership);
    }

    // Contractor catalogue

    @Override
    public Optional<Contractor> find(ContractorId id) {
        return Optional.ofNullable(contractors.get(id));
    }

    @Override
    public List<Contractor> findCovering(BuildingId building) {
        return contractors.values().stream().filter(c -> c.coverage().contains(building)).toList();
    }

    // Interventions

    @Override
    public void add(Intervention intervention, ProposalVersion firstProposal) {
        put(interventions, intervention.id(), intervention);
        addProposal(firstProposal);
    }

    @Override
    public Optional<Intervention> find(InterventionId id) {
        return Optional.ofNullable(interventions.get(id));
    }

    @Override
    public Optional<Intervention> findForUpdate(InterventionId id) {
        return find(id);
    }

    @Override
    public Optional<InterventionId> findActiveForIssue(IssueId issueId) {
        return interventions.values().stream()
                .filter(i -> i.issueId().equals(issueId) && !i.state().isTerminal())
                .map(Intervention::id)
                .findFirst();
    }

    @Override
    public void update(Intervention intervention) {
        if (!interventions.containsKey(intervention.id())) {
            throw new IllegalStateException("intervention to update does not exist");
        }
        put(interventions, intervention.id(), intervention);
    }

    @Override
    public Optional<ProposalVersion> findProposal(InterventionId id, int number) {
        return proposals.stream().filter(p -> p.interventionId().equals(id) && p.number() == number).findFirst();
    }

    @Override
    public Optional<ProposalVersion> findProposalForUpdate(InterventionId id, int number) {
        return findProposal(id, number);
    }

    @Override
    public Optional<ProposalVersion> findProposalCreatedAt(InterventionId id, long interventionVersion) {
        return proposals.stream()
                .filter(p -> p.interventionId().equals(id) && p.interventionVersion() == interventionVersion)
                .findFirst();
    }

    @Override
    public void addProposal(ProposalVersion proposal) {
        if (findProposal(proposal.interventionId(), proposal.number()).isPresent()) {
            throw new IllegalStateException("proposal version already exists");
        }
        proposals.add(proposal);
        journal(() -> proposals.remove(proposal));
    }

    @Override
    public void recordApproval(ProposalVersion approved) {
        ProposalVersion previous = findProposal(approved.interventionId(), approved.number()).orElseThrow();
        if (previous.approval().isPresent()) {
            throw new IllegalStateException("proposal version already approved");
        }
        int index = proposals.indexOf(previous);
        proposals.set(index, approved);
        journal(() -> proposals.set(proposals.indexOf(approved), previous));
    }

    @Override
    public void addResponse(ContractorResponse response) {
        if (responses.stream().anyMatch(r -> r.interventionId().equals(response.interventionId())
                && r.proposalVersion() == response.proposalVersion())) {
            throw new IllegalStateException("version already answered");
        }
        responses.add(response);
        journal(() -> responses.remove(response));
    }

    // Outbox

    @Override
    public void append(OutboxEvent event) {
        outbox.add(event);
        journal(() -> outbox.remove(event));
    }

    // Idempotency keys

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
        put(keys, record.scope(), record);
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

    /** A clock the tests move by hand. */
    public static final class MutableClock extends Clock {

        private Instant now;

        public MutableClock(Instant now) {
            this.now = now;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
