package ca.uhn.fhir.jpa.dao.data;

import ca.uhn.fhir.jpa.model.entity.MbInstalledStructureDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MbInstalledStructureDefinitionRepository
	extends JpaRepository<MbInstalledStructureDefinitionEntity, Long> {
  
  @Query("SELECT e FROM MbInstalledStructureDefinitionEntity e WHERE e.isValidatable = TRUE")
  List<MbInstalledStructureDefinitionEntity> findAllValidatable();
}
