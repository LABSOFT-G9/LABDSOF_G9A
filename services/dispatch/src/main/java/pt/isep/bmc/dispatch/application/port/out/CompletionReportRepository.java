package pt.isep.bmc.dispatch.application.port.out;

import java.util.Optional;
import pt.isep.bmc.dispatch.domain.intervention.CompletionReport;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;

/** Completion reports (US-09), every one kept. Written under the intervention's lock. */
public interface CompletionReportRepository {

    void add(CompletionReport report);

    Optional<CompletionReport> findLatest(InterventionId interventionId);

    /** Stores a rework request on an existing report; nothing else of a report ever changes. */
    void recordRework(CompletionReport report);
}
