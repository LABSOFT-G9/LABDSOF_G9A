package pt.isep.bmc.notification.domain.notification;

import static pt.isep.bmc.notification.domain.notification.ParamKind.ID;
import static pt.isep.bmc.notification.domain.notification.ParamKind.LABEL;
import static pt.isep.bmc.notification.domain.notification.ParamKind.TIME;
import static pt.isep.bmc.notification.domain.notification.ParamKind.TOKEN;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The templates the service accepts (US-10-01). A request for an unknown
 * template, or with a parameter the template does not list, is refused, so a
 * producer cannot slip report text into a notification through an extra
 * parameter.
 *
 * <p>The keys follow the workflow notification table of ADR-007 and cover the
 * US-10 updates: triage, contractor response, booking, completion and closure.
 * Recipients are the producer's decision (Issue for residents and
 * administrators, Dispatch for contractor-only ones), not the catalogue's.
 * Templates addressed to residents and administrators link to the issue page
 * of the client, which Issue authorises on every read. Contractor templates
 * have no link until the client has a contractor page.
 *
 * <p>A proposal of US-10-01: the producer side (US-10-03, #110) must use
 * these keys and parameters.
 */
public final class TemplateCatalogue {

    private static final String ISSUE_PAGE = "/issues/{issueId}";

    private static final Map<String, Template> TEMPLATES = index(List.of(
            new Template("report.received", Map.of("reportId", ID), Map.of(),
                    "Your report was received.", "/reports/{reportId}"),
            new Template("issue.triaged", Map.of("issueId", ID), Map.of("category", TOKEN, "urgency", TOKEN),
                    "An issue you follow was triaged.", ISSUE_PAGE),
            new Template("issue.summary_published", Map.of("issueId", ID), Map.of("areaLabel", LABEL),
                    "A shared summary was published for an issue in your building.", ISSUE_PAGE),
            new Template("intervention.contractor_responded", Map.of("issueId", ID, "interventionId", ID,
                    "response", TOKEN), Map.of(),
                    "The contractor responded to the work request.", ISSUE_PAGE),
            new Template("booking.confirmed", Map.of("issueId", ID, "interventionId", ID),
                    Map.of("windowStart", TIME, "windowEnd", TIME),
                    "A visit was booked for an issue you follow.", ISSUE_PAGE),
            new Template("booking.not_recorded", Map.of("issueId", ID, "interventionId", ID), Map.of(),
                    "A visit could not be booked in the calendar.", ISSUE_PAGE),
            new Template("booking.cancelled", Map.of("issueId", ID, "interventionId", ID), Map.of(),
                    "A booked visit was cancelled.", ISSUE_PAGE),
            new Template("intervention.completed", Map.of("issueId", ID, "interventionId", ID), Map.of(),
                    "The contractor reported the work as completed.", ISSUE_PAGE),
            new Template("issue.resolved", Map.of("issueId", ID), Map.of("resolutionKind", TOKEN),
                    "An issue you follow was resolved.", ISSUE_PAGE),
            new Template("issue.cancelled", Map.of("issueId", ID), Map.of(),
                    "An issue you follow was cancelled.", ISSUE_PAGE),
            new Template("issue.reopened", Map.of("issueId", ID), Map.of("reasonCode", TOKEN),
                    "An issue you follow was reopened.", ISSUE_PAGE),
            new Template("contractor.request_sent", Map.of("interventionId", ID), Map.of("areaLabel", LABEL),
                    "You have a new work request.", null),
            new Template("contractor.booking_cancelled", Map.of("interventionId", ID), Map.of(),
                    "A booked visit was cancelled.", null)));

    /** Shown for a stored notification whose template was later removed from the catalogue. */
    public static final String FALLBACK_TITLE = "You have an update.";

    private TemplateCatalogue() {
    }

    public static Optional<Template> find(String key) {
        return Optional.ofNullable(TEMPLATES.get(key));
    }

    public static List<Template> all() {
        return List.copyOf(TEMPLATES.values());
    }

    private static Map<String, Template> index(List<Template> templates) {
        Map<String, Template> byKey = new LinkedHashMap<>();
        for (Template template : templates) {
            if (byKey.putIfAbsent(template.key(), template) != null) {
                throw new IllegalStateException("duplicate template " + template.key());
            }
        }
        return Map.copyOf(byKey);
    }
}
