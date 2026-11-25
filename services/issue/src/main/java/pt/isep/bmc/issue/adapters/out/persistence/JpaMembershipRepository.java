package pt.isep.bmc.issue.adapters.out.persistence;

import java.util.List;
import org.springframework.stereotype.Repository;
import pt.isep.bmc.issue.application.port.out.MembershipRepository;
import pt.isep.bmc.issue.domain.membership.Membership;
import pt.isep.bmc.issue.domain.membership.UserId;

@Repository
class JpaMembershipRepository implements MembershipRepository {

    private final MembershipJpaRepository memberships;

    JpaMembershipRepository(MembershipJpaRepository memberships) {
        this.memberships = memberships;
    }

    @Override
    public List<Membership> findAll() {
        return memberships.findAll().stream().map(PersistenceMapper::toDomain).toList();
    }

    @Override
    public List<Membership> findByUser(UserId userId) {
        return memberships.findByUserId(userId.value()).stream().map(PersistenceMapper::toDomain).toList();
    }
}
