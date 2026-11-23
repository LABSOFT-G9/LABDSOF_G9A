package pt.isep.bmc.issue.adapters.in.rest;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import pt.isep.bmc.issue.adapters.in.rest.dto.AreaResponse;
import pt.isep.bmc.issue.application.error.NotFoundException;
import pt.isep.bmc.issue.application.port.in.ListBuildingAreasQuery;
import pt.isep.bmc.issue.domain.building.BuildingId;

/** Areas for the report form (US-01-02, #86): members of the building only. */
@RestController
class BuildingController {

    private final ListBuildingAreasQuery listAreas;

    BuildingController(ListBuildingAreasQuery listAreas) {
        this.listAreas = listAreas;
    }

    @GetMapping(path = "/buildings/{buildingId}/areas", produces = MediaType.APPLICATION_JSON_VALUE)
    List<AreaResponse> areas(@AuthenticationPrincipal Jwt jwt, @PathVariable String buildingId) {
        BuildingId building = new BuildingId(RequestIds.parse(buildingId).orElseThrow(NotFoundException::new));
        return listAreas.list(Actors.of(jwt), building).stream()
                .map(a -> new AreaResponse(a.id().toString(), a.label(), a.kind().value()))
                .toList();
    }
}
