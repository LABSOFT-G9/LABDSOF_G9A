package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** Body of {@code POST /interventions} (technical design annex A 1.3). Every field is checked by the domain. */
public record CreateInterventionRequest(
        String issueId, String contractorId, String scopeExtract, WindowBody window, String terms) {

    /** RFC 3339 timestamps with an offset; stored in UTC. */
    public record WindowBody(String start, String end) {
    }

    /** Never prints free text: Spring logs request bodies at DEBUG. */
    @Override
    public String toString() {
        return "CreateInterventionRequest[issueId=" + issueId + ", contractorId=" + contractorId + "]";
    }
}
