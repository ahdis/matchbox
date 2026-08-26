package ca.uhn.fhir.jpa.dao.data;

import ca.uhn.fhir.jpa.model.entity.MbInstalledStructureDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The Spring Repository for {@link MbInstalledStructureDefinitionEntity}.
 * See {@link MbInstalledStructureDefinitionEntity} for details on the table and its columns, and why it's used.
 */
public interface MbInstalledStructureDefinitionRepository
  extends JpaRepository<MbInstalledStructureDefinitionEntity, Long> {

  @Query("SELECT e FROM MbInstalledStructureDefinitionEntity e WHERE e.isValidatable = TRUE ORDER BY e.title ASC")
  List<MbInstalledStructureDefinitionEntity> findAllValidatable();
  
  @Query("SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END FROM MbInstalledStructureDefinitionEntity e " +
    "WHERE e.canonicalUrl = :canonical AND e.type = :type")
  boolean existsByCanonicalAndType(@Param("canonical") final String canonical,
                                   @Param("type") final String type);
}