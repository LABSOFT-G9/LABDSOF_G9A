package pt.isep.bmc.issue.adapters.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to membership rows; used only by {@link JpaMembershipRepository}. */
interface MembershipJpaRepository extends JpaRepository<MembershipEntity, String> {

    List<MembershipEntity> findByUserId(String userId);
}
