package pt.isep.bmc.notification.adapters.in.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.bmc.notification.adapters.in.rest.dto.MarkedReadResponse;
import pt.isep.bmc.notification.adapters.in.rest.dto.NotificationListResponse;
import pt.isep.bmc.notification.adapters.in.rest.dto.UnreadCountResponse;
import pt.isep.bmc.notification.application.port.in.CountUnreadQuery;
import pt.isep.bmc.notification.application.port.in.ListNotificationsQuery;
import pt.isep.bmc.notification.application.port.in.MarkNotificationsReadUseCase;
import pt.isep.bmc.notification.application.query.NotificationPage;
import pt.isep.bmc.notification.domain.shared.FieldViolation;
import pt.isep.bmc.notification.domain.shared.ValidationException;

/**
 * The caller's in-app inbox (technical design 4.2, ADR-007). Paths are
 * service-relative; the edge serves them under {@code /api/notifications}, so
 * the client calls {@code GET /api/notifications/notifications}.
 */
@RestController
@RequestMapping(path = "/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
class NotificationController {

    private final ListNotificationsQuery notifications;
    private final CountUnreadQuery unread;
    private final MarkNotificationsReadUseCase readState;

    NotificationController(
            ListNotificationsQuery notifications, CountUnreadQuery unread, MarkNotificationsReadUseCase readState) {
        this.notifications = notifications;
        this.unread = unread;
        this.readState = readState;
    }

    /**
     * The caller's own notifications, newest first. {@code limit} is 1 to 100
     * (default 20); {@code before} is the {@code nextBefore} of the previous
     * page.
     */
    @GetMapping
    NotificationListResponse list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "before", required = false) String before) {
        List<FieldViolation> violations = new ArrayList<>();
        int parsedLimit = limit(limit, violations);
        Optional<UUID> cursor = Optional.empty();
        if (before != null) {
            cursor = RequestIds.parse(before);
            if (cursor.isEmpty()) {
                violations.add(new FieldViolation("before", "invalid_format"));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        NotificationPage page = notifications.list(Actors.of(jwt), cursor, parsedLimit);
        return new NotificationListResponse(
                page.items().stream()
                        .map(n -> new NotificationListResponse.Item(
                                n.id().toString(),
                                n.templateKey(),
                                n.title(),
                                n.link().orElse(null),
                                n.priority().token(),
                                n.params(),
                                n.createdAt().toString(),
                                n.isRead(),
                                n.readAtTime().map(Object::toString).orElse(null)))
                        .toList(),
                page.nextBefore().map(UUID::toString).orElse(null));
    }

    /** How many of the caller's notifications are unread, for a badge. */
    @GetMapping("/unread-count")
    UnreadCountResponse unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return new UnreadCountResponse(unread.countUnread(Actors.of(jwt)));
    }

    /**
     * Marks one of the caller's notifications read: {@code 204}, also when it
     * was already read. {@code 404} when the caller has no notification with
     * this ID, another user's included.
     */
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        RequestIds.check(id, "id").ifPresent(violation -> {
            throw new ValidationException(List.of(violation));
        });
        readState.markRead(Actors.of(jwt), RequestIds.parse(id).orElseThrow());
    }

    /** Marks every unread notification of the caller read. */
    @PostMapping("/read-all")
    MarkedReadResponse markAllRead(@AuthenticationPrincipal Jwt jwt) {
        return new MarkedReadResponse(readState.markAllRead(Actors.of(jwt)));
    }

    private static int limit(String raw, List<FieldViolation> violations) {
        if (raw == null) {
            return ListNotificationsQuery.DEFAULT_LIMIT;
        }
        if (!raw.matches("[0-9]{1,3}")) {
            violations.add(new FieldViolation("limit", "invalid_format"));
            return ListNotificationsQuery.DEFAULT_LIMIT;
        }
        int value = Integer.parseInt(raw);
        if (value < 1 || value > ListNotificationsQuery.MAX_LIMIT) {
            violations.add(new FieldViolation("limit", "out_of_range"));
            return ListNotificationsQuery.DEFAULT_LIMIT;
        }
        return value;
    }
}
