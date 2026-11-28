package pt.isep.bmc.dispatch.application.query;

/**
 * One intervention as its reader may see it: the building's administrator
 * sees everything ({@link InterventionView}), the assigned contractor only
 * their request ({@link AssignmentView}).
 */
public sealed interface InterventionRead permits AssignmentView, InterventionView {

    long version();
}
