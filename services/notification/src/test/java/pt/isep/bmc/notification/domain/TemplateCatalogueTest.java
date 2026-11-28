package pt.isep.bmc.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.isep.bmc.notification.domain.notification.Notification;
import pt.isep.bmc.notification.domain.notification.ParamKind;
import pt.isep.bmc.notification.domain.notification.Priority;
import pt.isep.bmc.notification.domain.notification.Template;
import pt.isep.bmc.notification.domain.notification.TemplateCatalogue;
import pt.isep.bmc.notification.domain.shared.UserId;

class TemplateCatalogueTest {

    static final String ISSUE = "0199f2a1-0000-7000-8000-000000002001";

    @Test
    void theUs10UpdatesAreCovered() {
        assertThat(TemplateCatalogue.all()).extracting(Template::key).contains(
                "issue.triaged",                      // triage
                "intervention.contractor_responded",  // contractor response
                "booking.confirmed",                  // booking
                "intervention.completed",             // completion
                "issue.resolved");                    // closure
    }

    @Test
    void everyContractorNotificationOfTheDesignHasATemplate() {
        // Technical design 4.4: Dispatch events that carry a contractorUserId, so Issue can address the contractor.
        assertThat(TemplateCatalogue.all()).extracting(Template::key).contains(
                "contractor.request_sent",       // InterventionApproved
                "contractor.terms_changed",      // InterventionTermsChanged
                "contractor.request_cancelled",  // InterventionCancelled before a booking
                "contractor.booking_cancelled",  // InterventionCancelled after a booking
                "contractor.rework_requested");  // ReworkRequested
    }

    @Test
    void noTemplateTakesFreeTextAndEveryLinkIsAClientPath() {
        for (Template template : TemplateCatalogue.all()) {
            assertThat(template.required().values()).doesNotContain(ParamKind.LABEL);
            if (template.linkPath() != null) {
                assertThat(template.linkPath()).startsWith("/");
            }
        }
    }

    @Test
    void residentAndAdministratorUpdatesLinkToTheIssuePageAndContractorOnesHaveNoLink() {
        assertThat(inbox("booking.confirmed", Map.of("issueId", ISSUE, "interventionId", ISSUE)).link())
                .contains("/issues/" + ISSUE);
        assertThat(inbox("contractor.request_sent", Map.of("interventionId", ISSUE)).link()).isEmpty();
    }

    @Test
    void aStoredNotificationWhoseTemplateWasRemovedStillReads() {
        Notification old = inbox("issue.retired_template", Map.of("issueId", ISSUE));
        assertThat(old.title()).isEqualTo(TemplateCatalogue.FALLBACK_TITLE);
        assertThat(old.link()).isEmpty();
    }

    @Test
    void aLinkCanOnlyUseRequiredIdParameters() {
        assertThatThrownBy(() -> new Template("x", Map.of(), Map.of("issueId", ParamKind.ID), "t", "/issues/{issueId}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Template("x", Map.of("area", ParamKind.LABEL), Map.of(), "t", "/a/{area}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Notification inbox(String key, Map<String, String> params) {
        return new Notification(UUID.randomUUID(), UUID.randomUUID(), new UserId("u"), key, params,
                Priority.NORMAL, "e-1", Instant.parse("2026-11-28T10:00:00Z"), null);
    }

    @Test
    void templateKeysAreUnique() {
        List<String> keys = TemplateCatalogue.all().stream().map(Template::key).toList();
        assertThat(keys).doesNotHaveDuplicates();
    }
}
