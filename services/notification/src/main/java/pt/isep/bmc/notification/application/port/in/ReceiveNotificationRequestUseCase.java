package pt.isep.bmc.notification.application.port.in;

import pt.isep.bmc.notification.application.command.Receipt;
import pt.isep.bmc.notification.domain.notification.NotificationRequest;

/**
 * Turns a {@code NotificationRequested} into one notification per recipient,
 * delivered on every channel (US-10-01, ADR-007). Idempotent per
 * {@code notificationId}, recipient and channel: repeating a request, also
 * concurrently or after a restart, delivers nothing again.
 */
public interface ReceiveNotificationRequestUseCase {

    Receipt receive(NotificationRequest request);
}
