package ch.ahdis.matchbox.packages.migrations;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.binary.api.IBinaryStorageSvc;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionResourceDao;
import ca.uhn.fhir.jpa.dao.data.MbInstalledStructureDefinitionRepository;
import ca.uhn.fhir.jpa.model.entity.MbInstalledStructureDefinitionEntity;
import ch.ahdis.matchbox.packages.documents.DocumentCompositionCodesExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Migrates MB_INSTALLED_STRUCT_DEF rows still at meta version 1 to meta version 2: extracts the document
 * type/category codes ({@link MbInstalledStructureDefinitionEntity#getDocCompTypeCode()} /
 * {@link MbInstalledStructureDefinitionEntity#getDocCompCatCode()}) for rows that are document-type Bundles, then
 * bumps their {@code metaVersion} to {@link MbInstalledStructureDefinitionEntity#CURRENT_META_VERSION}.
 *
 * @author Quentin Ligier
 **/
public class MbInstalledStructureDefinitionV2Migration {
	private static final Logger LOG = LoggerFactory.getLogger(MbInstalledStructureDefinitionV2Migration.class);
	private static final int PAGE_SIZE = 50;

	private static final byte PREVIOUS_META_VERSION = 1;
	private static final byte THIS_META_VERSION = 2;

	private final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository;
	private final DocumentCompositionCodesExtractor extractor;
	private final TransactionTemplate pageTxTemplate;
	private final TransactionTemplate entityTxTemplate;

	public MbInstalledStructureDefinitionV2Migration(final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository,
	                                                 final DaoRegistry myDaoRegistry,
	                                                 final IBinaryStorageSvc myBinaryStorageSvc,
	                                                 final INpmPackageVersionResourceDao myPackageVersionResourceDao,
	                                                 final PlatformTransactionManager txManager) {
		this.installedStructureDefinitionRepository = requireNonNull(installedStructureDefinitionRepository);
		this.extractor = new DocumentCompositionCodesExtractor(requireNonNull(myDaoRegistry),
		                                                       requireNonNull(myBinaryStorageSvc),
		                                                       requireNonNull(myPackageVersionResourceDao),
		                                                       this.installedStructureDefinitionRepository);
		this.pageTxTemplate = new TransactionTemplate(requireNonNull(txManager));
		this.entityTxTemplate = new TransactionTemplate(txManager);
		this.entityTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * Deliberately not {@code @Transactional}: each page gets its own transaction via {@link #pageTxTemplate} in
	 * {@link #migratePage}.
	 * <p>
	 * Every page is (re-)fetched as page 0 of the rows still at {@link #PREVIOUS_META_VERSION}, rather than
	 * advancing through successive offsets: each row that gets migrated leaves that filtered set, so what was
	 * "page 1" before this page ran is "page 0" afterward. The number of iterations is capped to the number of
	 * pages the original count of version-1 rows would need, so that a handful of rows that can never be saved
	 * (e.g. a constraint violation) - which would otherwise keep re-appearing in every "page 0" forever - can't
	 * turn this into an infinite loop; they're simply left at {@link #PREVIOUS_META_VERSION} to be retried on the
	 * next startup, same as a bad row is handled in {@link MbInstalledStructureDefinitionV1Migration}.
	 */
	public void run() {
		final long remaining = this.installedStructureDefinitionRepository.countByMetaVersion(PREVIOUS_META_VERSION);
		final long maxPages = (remaining + PAGE_SIZE - 1) / PAGE_SIZE;
		LOG.info("Migrating {} MB_INSTALLED_STRUCT_DEF rows to meta version {}", THIS_META_VERSION);

		final Pageable page = PageRequest.of(0, PAGE_SIZE);
		for (long i = 0; i < maxPages; ++i) {
			LOG.trace("Migrating MB_INSTALLED_STRUCT_DEF page {}/{} (size {})", i + 1, maxPages, PAGE_SIZE);
			if (!this.migratePage(page)) {
				break;
			}
		}
		LOG.debug("MB_INSTALLED_STRUCT_DEF migration to meta version {} complete", THIS_META_VERSION);
	}

	/**
	 * Migrates one page of rows: reads it and updates the entities in one transaction ({@link #buildMigratedPage}),
	 * then saves them ({@link #saveEntities}).
	 *
	 * @return whether there is a next page to process.
	 */
	private boolean migratePage(final Pageable page) {
		final PageMigration migration = requireNonNull(this.pageTxTemplate.execute(status -> this.buildMigratedPage(page)));
		if (!migration.entities().isEmpty()) {
			this.saveEntities(migration.entities());
		}
		return migration.hasNext();
	}

	/**
	 * Reads one page of rows still at {@link #PREVIOUS_META_VERSION} and updates each entity in place (see
	 * {@link #migrateEntity}), without saving anything yet. Runs inside the page-level transaction opened by
	 * {@link #migratePage}, so that lazily-loaded associations (e.g.
	 * {@link MbInstalledStructureDefinitionEntity#getNpmPackageVersionResourceEntity()}) can be resolved.
	 */
	private PageMigration buildMigratedPage(final Pageable page) {
		final Slice<MbInstalledStructureDefinitionEntity> slice = this.installedStructureDefinitionRepository
				.findAllByMetaVersion(PREVIOUS_META_VERSION, page);
		final List<MbInstalledStructureDefinitionEntity> entities = new ArrayList<>(slice.getNumberOfElements());
		for (final MbInstalledStructureDefinitionEntity entity : slice.getContent()) {
			this.migrateEntity(entity);
			entities.add(entity);
		}
		return new PageMigration(entities, slice.hasNext());
	}

	/**
	 * Updates one entity in place: extracts and sets the document type/category codes if it's a document-type
	 * Bundle, then bumps its meta version. Extraction failures are logged and swallowed - a StructureDefinition
	 * that can't be parsed shouldn't stop the whole row from being migrated to the current meta version.
	 */
	private void migrateEntity(final MbInstalledStructureDefinitionEntity entity) {
		if ("resource".equals(entity.getKind()) && "Bundle".equals(entity.getType())) {
			try {
				final var codes = this.extractor.extractCodes(entity.getNpmPackageVersionResourceEntity());
				if (codes != null) {
					entity.setDocCompTypeCode(codes.typeCode());
					entity.setDocCompCatCode(codes.categoryCode());
				}
			} catch (final Exception e) {
				LOG.warn("Failed to extract document type/category codes from StructureDefinition '{}'",
						entity.getCanonicalUrl(), e);
			}
		}
		entity.setMetaVersion(THIS_META_VERSION);
	}

	/**
	 * Saves a page's worth of rows.
	 * <p>
	 * Fast path: the whole page is saved as a single batch, in a single transaction/commit - this is what almost
	 * every page will hit, since bad rows should be rare. Only if that batch fails does this fall back to
	 * retrying each row individually, each in its own {@code REQUIRES_NEW} transaction - see the equivalent
	 * {@code saveEntities} method in {@link MbInstalledStructureDefinitionV1Migration} for the full rationale
	 * (a single failed statement poisons the rest of the transaction on Postgres), which applies here unchanged.
	 */
	private void saveEntities(final List<MbInstalledStructureDefinitionEntity> entities) {
		try {
			this.pageTxTemplate.executeWithoutResult(
					status -> this.installedStructureDefinitionRepository.saveAll(entities));
			return;
		} catch (final Exception e) {
			LOG.debug("MATCHBOX: batch save of {} migrated MB_INSTALLED_STRUCT_DEF rows failed, retrying them individually",
					entities.size(), e);
		}
		for (final MbInstalledStructureDefinitionEntity entity : entities) {
			try {
				this.entityTxTemplate.executeWithoutResult(
						status -> this.installedStructureDefinitionRepository.save(entity));
			} catch (final Exception e) {
				LOG.error("MATCHBOX: failed to save migrated MB_INSTALLED_STRUCT_DEF row for canonical URL '{}'",
						entity.getCanonicalUrl(), e);
			}
		}
	}

	/**
	 * The entities updated from one page, ready to be saved, and whether there is a next page to process.
	 */
	private record PageMigration(List<MbInstalledStructureDefinitionEntity> entities, boolean hasNext) {
	}
}
