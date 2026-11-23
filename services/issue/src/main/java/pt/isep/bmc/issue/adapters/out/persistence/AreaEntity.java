package pt.isep.bmc.issue.adapters.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "areas")
class AreaEntity {

    @Id
    @Column(name = "id", length = 36)
    String id;

    @Column(name = "building_id", nullable = false, length = 36)
    String buildingId;

    @Column(name = "label", nullable = false)
    String label;

    @Column(name = "kind", nullable = false)
    String kind;

    protected AreaEntity() {
    }
}
