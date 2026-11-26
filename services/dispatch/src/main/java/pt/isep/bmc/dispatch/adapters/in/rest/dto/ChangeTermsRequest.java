package pt.isep.bmc.dispatch.adapters.in.rest.dto;

/** Body of {@code POST /interventions/{id}/proposals}: the same terms as a draft, without the contractor. */
public record ChangeTermsRequest(String scopeExtract, CreateInterventionRequest.WindowBody window, String terms) {

    /** Never prints free text: Spring logs request bodies at DEBUG. */
    @Override
    public String toString() {
        return "ChangeTermsRequest[]";
    }
}
