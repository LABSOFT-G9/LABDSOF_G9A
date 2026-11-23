package pt.isep.bmc.issue.adapters.in.rest.dto;

/** Body of {@code POST /reports}. {@link #toString()} redacts the text. */
public record SubmitReportRequest(String buildingId, String areaId, String text) {

    @Override
    public String toString() {
        return "SubmitReportRequest[buildingId=" + buildingId + ", areaId=" + areaId + ", text=redacted]";
    }
}
