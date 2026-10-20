package com.perfplatform.persistence.dataset;

import com.perfplatform.domain.dataset.DatasetUri;
import com.perfplatform.domain.identity.DatasetId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.DatasetCatalog;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The durable record of dataset versions, in PostgreSQL.
 *
 * <p>The one rule it enforces is immutability, and it enforces it with the unique constraint rather
 * than with a prior read. Two people uploading {@code orders} at the same moment would both see no
 * version 4 and both insert one; the database is the only place that can decide which of them won.
 * The loser is told to upload the next version, which is the truthful answer — their rows are not
 * what version 4 holds.
 */
@Repository
public class DatasetCatalogStore implements DatasetCatalog {

    private final DatasetJpaRepository datasets;

    public DatasetCatalogStore(DatasetJpaRepository datasets) {
        this.datasets = datasets;
    }

    @Override
    @Transactional
    public DatasetVersion register(DatasetVersion version) {
        try {
            datasets.saveAndFlush(new DatasetEntity(
                    version.id().value(),
                    version.teamId().value(),
                    version.name(),
                    version.version(),
                    version.objectKey(),
                    version.digest(),
                    String.join(",", version.columns()),
                    version.rowCount(),
                    version.sizeBytes(),
                    version.uploadedBy(),
                    version.uploadedAt()));
        } catch (DataIntegrityViolationException e) {
            // The unique constraint is the immutability guarantee. Translating it here means the
            // caller gets the port's own type rather than a data-access exception it would have to
            // interpret — and could get wrong.
            throw new DatasetVersionExistsException(version.uri());
        }
        return version;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DatasetVersion> find(TeamId teamId, String name, int version) {
        return datasets.findByTeamIdAndNameAndVersion(teamId.value(), name, version)
                .map(DatasetCatalogStore::toVersion);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DatasetVersion> listForTeam(TeamId teamId) {
        return datasets.findByTeamIdOrderByNameAscVersionDesc(teamId.value()).stream()
                .map(DatasetCatalogStore::toVersion)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public int latestVersion(TeamId teamId, String name) {
        return datasets.findFirstByTeamIdAndNameOrderByVersionDesc(teamId.value(), name)
                .map(DatasetEntity::getVersion)
                .orElse(0);
    }

    private static DatasetVersion toVersion(DatasetEntity entity) {
        return new DatasetVersion(
                DatasetId.of(entity.getId()),
                TeamId.of(entity.getTeamId()),
                DatasetUri.of(entity.getName(), entity.getVersion()),
                entity.getObjectKey(),
                entity.getDigest(),
                columnsOf(entity.getColumnsCsv()),
                entity.getRowCount(),
                entity.getSizeBytes(),
                entity.getUploadedBy(),
                entity.getUploadedAt());
    }

    /**
     * The stored header back into a list.
     *
     * <p>A plain split, because a column name is restricted to what a {@code ${name}} reference can
     * be and therefore cannot contain a comma. Storing them as rows would be the general answer to a
     * problem this schema does not have.
     */
    private static List<String> columnsOf(String columnsCsv) {
        return columnsCsv.isBlank() ? List.of() : Arrays.asList(columnsCsv.split(","));
    }
}
