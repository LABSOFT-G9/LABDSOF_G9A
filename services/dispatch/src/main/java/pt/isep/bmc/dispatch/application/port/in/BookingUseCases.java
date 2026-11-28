package pt.isep.bmc.dispatch.application.port.in;

import pt.isep.bmc.dispatch.application.command.BookingCommandResult;
import pt.isep.bmc.dispatch.application.command.CancelInterventionCommand;
import pt.isep.bmc.dispatch.application.command.ManualConfirmationCommand;
import pt.isep.bmc.dispatch.application.command.RequestBookingCommand;
import pt.isep.bmc.dispatch.domain.intervention.InterventionId;
import pt.isep.bmc.dispatch.domain.shared.UserId;

/**
 * The administrator's booking commands (US-08) and the cancellation of an
 * intervention (technical design 3.2).
 */
public interface BookingUseCases {

    /** {@code POST /interventions/{id}/manual-confirmation}. */
    BookingCommandResult confirmManually(ManualConfirmationCommand command);

    /** {@code POST /interventions/{id}/booking}: another request after {@code rejected} or {@code failed}. */
    BookingCommandResult requestAgain(RequestBookingCommand command);

    /** {@code POST /interventions/{id}/booking/reconciliation}: check the confirmed booking now. */
    BookingCommandResult reconcile(UserId actor, InterventionId interventionId);

    /** {@code POST /interventions/{id}/cancellation}. */
    BookingCommandResult cancel(CancelInterventionCommand command);
}
