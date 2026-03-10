package dalili.com.base.domain.encounter.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.UUID;

/**
 * Base repository that prevents deletion of clinical records.
 *
 * <p>This is a safety measure to ensure clinical records are never
 * deleted, even if code accidentally calls delete methods.</p>
 */
@NoRepositoryBean
public interface ImmutableClinicalRepository<T> extends JpaRepository<T, UUID> {

    /**
     * Deletion is not supported for clinical records.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default void delete(T entity) {
        throw new UnsupportedOperationException(
                "Deletion of clinical records is not permitted. " +
                        "Use cancellation or addendum instead."
        );
    }

    /**
     * Deletion is not supported for clinical records.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default void deleteById(UUID id) {
        throw new UnsupportedOperationException(
                "Deletion of clinical records is not permitted. " +
                        "Use cancellation or addendum instead."
        );
    }

    /**
     * Deletion is not supported for clinical records.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default void deleteAll() {
        throw new UnsupportedOperationException(
                "Deletion of clinical records is not permitted."
        );
    }

    /**
     * Deletion is not supported for clinical records.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default void deleteAll(Iterable<? extends T> entities) {
        throw new UnsupportedOperationException(
                "Deletion of clinical records is not permitted."
        );
    }

    /**
     * Deletion is not supported for clinical records.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default void deleteAllById(Iterable<? extends UUID> ids) {
        throw new UnsupportedOperationException(
                "Deletion of clinical records is not permitted."
        );
    }

    /**
     * Deletion is not supported for clinical records.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default void deleteAllInBatch() {
        throw new UnsupportedOperationException(
                "Deletion of clinical records is not permitted."
        );
    }

    /**
     * Deletion is not supported for clinical records.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default void deleteAllInBatch(Iterable<T> entities) {
        throw new UnsupportedOperationException(
                "Deletion of clinical records is not permitted."
        );
    }

    /**
     * Deletion is not supported for clinical records.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default void deleteAllByIdInBatch(Iterable<UUID> ids) {
        throw new UnsupportedOperationException(
                "Deletion of clinical records is not permitted."
        );
    }
}
