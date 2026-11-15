package ca.uhn.fhir.jpa.dao.data;

import ca.uhn.fhir.jpa.model.entity.MbInstalledStructureDefinitionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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

  // The order is total, and not only by title: the Gazelle profile list is served with an ETag computed over its
  // serialization, which is only stable if two calls with the same data return the rows in the same order.
  @Query("SELECT e FROM MbInstalledStructureDefinitionEntity e WHERE e.isValidatable = TRUE "
    + "ORDER BY e.title ASC, e.canonicalUrl ASC, e.packageVersion ASC")
  List<MbInstalledStructureDefinitionEntity> findAllValidatable();
  
  @Query("SELECT e FROM MbInstalledStructureDefinitionEntity e WHERE e.canonicalUrl = :canonical")
  List<MbInstalledStructureDefinitionEntity> findAllByCanonical(@Param("canonical") String canonical);
  
  @Query("SELECT e FROM MbInstalledStructureDefinitionEntity e WHERE e.docCompTypeCode = ca.uhn.fhir.jpa.model.entity.MbInstalledStructureDefinitionEntity.DOC_BUNDLE_NEEDS_PROCESSING")
  List<MbInstalledStructureDefinitionEntity> findAllForDocumentBundleProcessing();
  
  @Query("SELECT e FROM MbInstalledStructureDefinitionEntity e WHERE e.docCompTypeCode = :typeCode AND (" +
    "e.docCompCatCode = :categoryCode OR e.docCompCatCode IS NULL)")
  List<MbInstalledStructureDefinitionEntity> findAllByDocumentTypeAndCategory(@Param("typeCode") String typeCode,
                                                                              @Param("categoryCode") String categoryCode);
  
  @Query("SELECT e FROM MbInstalledStructureDefinitionEntity e WHERE e.docCompTypeCode = :typeCode AND e.docCompCatCode IS NULL")
  List<MbInstalledStructureDefinitionEntity> findAllByDocumentTypeWithoutCategory(@Param("typeCode") String typeCode);
  
  @Query("SELECT e FROM MbInstalledStructureDefinitionEntity e WHERE e.docCompTypeCode IS NOT NULL OR e.docCompCatCode IS NOT NULL")
  List<MbInstalledStructureDefinitionEntity> findAllRecognizableDocuments();
  
  @Query("SELECT e FROM MbInstalledStructureDefinitionEntity e WHERE e.metaVersion = :metaVersion")
  Slice<MbInstalledStructureDefinitionEntity> findAllByMetaVersion(@Param("metaVersion") byte metaVersion,
                                                                   Pageable pageable);

  @Query("SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END FROM MbInstalledStructureDefinitionEntity e " +
    "WHERE e.canonicalUrl = :canonical AND e.type = :type")
  boolean existsByCanonicalAndType(@Param("canonical") String canonical,
                                   @Param("type") String type);

  boolean existsByMetaVersion(byte metaVersion);
  
  boolean existsByDocCompTypeCode(String docCompTypeCode);

  long countByMetaVersion(byte metaVersion);
}