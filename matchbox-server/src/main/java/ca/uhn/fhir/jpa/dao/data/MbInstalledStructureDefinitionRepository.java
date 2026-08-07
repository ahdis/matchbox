package ca.uhn.fhir.jpa.dao.data;

import ca.uhn.fhir.jpa.model.entity.MbInstalledStructureDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * The Spring Repository for {@link MbInstalledStructureDefinitionEntity}.
 * See {@link MbInstalledStructureDefinitionEntity} for details on the table and its columns, and why it's used.
 */
public interface MbInstalledStructureDefinitionRepository
  extends JpaRepository<MbInstalledStructureDefinitionEntity, Long> {

  @Query("SELECT e FROM MbInstalledStructureDefinitionEntity e WHERE e.isValidatable = TRUE ORDER BY e.title ASC")
  List<MbInstalledStructureDefinitionEntity> findAllValidatable();
}