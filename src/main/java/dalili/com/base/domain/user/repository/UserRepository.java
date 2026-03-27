package dalili.com.base.domain.user.repository;

import dalili.com.base.domain.user.model.Role;
import dalili.com.base.domain.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);

    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByUsernameAndActiveTrue(String username);

    Optional<User> findByUsernameIgnoreCaseAndActiveTrue(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    long countByRoleAndActiveTrue(Role role);
}
