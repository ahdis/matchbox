package ch.ahdis.matchbox.packages.migrations;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.binary.api.IBinaryStorageSvc;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionResourceDao;
import ca.uhn.fhir.jpa.dao.data.MbInstalledStructureDefinitionRepository;
import ca.uhn.fhir.jpa.model.entity.MbInstalledStructureDefinitionEntity;
import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionResourceEntity;
import ch.ahdis.matchbox.packages.MatchboxJpaPackageCache;
import org.hl7.fhir.instance.model.api.IBaseBinary;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Startup task that backfills the MB_INSTALLED_STRUCT_DEF table for installations upgraded.
 * <p>
 * The table itself doesn't need an explicit migration: it's created automatically by Hibernate (see
 * {@link ca.uhn.fhir.jpa.starter.util.EnvironmentHelper#getHibernateProperties}, wired into the real
 * EntityManagerFactory in {@code StarterJpaConfig}). What Hibernate cannot do is fill it with data: it is normally
 * populated by {@link MatchboxJpaPackageCache#interceptEntityAfterSaving} whenever a StructureDefinition is freshly
 * installed, so on an upgrade the StructureDefinitions installed by previous versions were never processed by that
 * hook and the table starts out empty.
 * <p>
 * Each page is processed in its own short transaction (see {@link #backfillPage}), rather than the whole backfill
 * running as one long transaction: the embedded web server starts accepting HTTP requests during context refresh,
 * i.e. before this ApplicationRunner even starts, so a single transaction spanning every installed
 * StructureDefinition (potentially thousands, across many pages) would hold locks on NPM_PACKAGE_VER_RES /
 * MB_INSTALLED_STRUCT_DEF for a long time while real traffic can already be hitting the same tables - a deadlock
 * waiting to happen, and it did.
 *
 * @author Quentin Ligier
 **/
public class MbInstalledStructureDefinitionV1Migration {
	private static final Logger LOG = LoggerFactory.getLogger(MbInstalledStructureDefinitionV1Migration.class);
	private static final int PAGE_SIZE = 250;

	private final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository;
	private final MatchboxJpaPackageCache matchboxJpaPackageCache;
	private final INpmPackageVersionResourceDao myPackageVersionResourceDao;
	private final IBinaryStorageSvc myBinaryStorageSvc;
	private final IFhirResourceDao<IBaseBinary> binaryDao;
	private final TransactionTemplate pageTxTemplate;
	private final TransactionTemplate entityTxTemplate;

	public MbInstalledStructureDefinitionV1Migration(final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository,
	                                                 final MatchboxJpaPackageCache matchboxJpaPackageCache,
	                                                 final INpmPackageVersionResourceDao myPackageVersionResourceDao,
	                                                 final IBinaryStorageSvc myBinaryStorageSvc,
	                                                 final DaoRegistry myDaoRegistry,
	                                                 final PlatformTransactionManager txManager) {
		this.installedStructureDefinitionRepository = requireNonNull(installedStructureDefinitionRepository);
		this.matchboxJpaPackageCache = requireNonNull(matchboxJpaPackageCache);
		this.myPackageVersionResourceDao = requireNonNull(myPackageVersionResourceDao);
		this.myBinaryStorageSvc = requireNonNull(myBinaryStorageSvc);
		this.binaryDao = (IFhirResourceDao<IBaseBinary>) myDaoRegistry.getResourceDao("Binary");
		this.pageTxTemplate = new TransactionTemplate(requireNonNull(txManager));
		// A separate, REQUIRES_NEW template, only used as a fallback when a page's batch save fails: see
		// #saveEntities for why.
		this.entityTxTemplate = new TransactionTemplate(txManager);
		this.entityTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * Deliberately not {@code @Transactional}: each page gets its own transaction via {@link #pageTxTemplate} in
	 * {@link #backfillPage}.
	 */
	public void run() {
		LOG.info("Backfilling MB_INSTALLED_STRUCT_DEF from the already-installed StructureDefinitions");
		Pageable page = PageRequest.of(0, PAGE_SIZE);
		boolean hasNext;
		do {
			LOG.trace("Backfilling MB_INSTALLED_STRUCT_DEF page {} (size {})", page.getPageNumber(), page.getPageSize());
			hasNext = this.backfillPage(page);
			page = page.next();
		} while (hasNext);
		LOG.debug("MB_INSTALLED_STRUCT_DEF migration complete");
	}

	/**
	 * Backfills one page of StructureDefinitions: reads it and rebuilds the entities in one transaction
	 * ({@link #buildPage}), then saves them ({@link #saveEntities}).
	 *
	 * @return whether there is a next page to process.
	 */
	private boolean backfillPage(final Pageable page) {
		final PageBuild build = requireNonNull(this.pageTxTemplate.execute(status -> this.buildPage(page)));
		if (!build.entities().isEmpty()) {
			this.saveEntities(build.entities());
		}
		return build.hasNext();
	}

	/**
	 * Reads one page of StructureDefinitions and rebuilds the MB_INSTALLED_STRUCT_DEF entity for each one (see
	 * {@link MatchboxJpaPackageCache#buildInstalledStructureDefinitionEntity}), without saving anything yet. Runs
	 * inside the page-level transaction opened by {@link #backfillPage}.
	 */
	private PageBuild buildPage(final Pageable page) {
		final Slice<NpmPackageVersionResourceEntity> slice =
				this.myPackageVersionResourceDao.findByResourceTypeOrdered(page, "StructureDefinition");
		final List<MbInstalledStructureDefinitionEntity> entities = new ArrayList<>(slice.getNumberOfElements());
		for (final NpmPackageVersionResourceEntity entity : slice.getContent()) {
			final MbInstalledStructureDefinitionEntity installedEntity = this.buildEntity(entity);
			if (installedEntity != null) {
				entities.add(installedEntity);
			}
		}
		return new PageBuild(entities, slice.hasNext());
	}

	/**
	 * Reads and parses one StructureDefinition's binary content and rebuilds its MB_INSTALLED_STRUCT_DEF entity,
	 * or returns {@code null} (logging the error) if that fails.
	 */
	private MbInstalledStructureDefinitionEntity buildEntity(final NpmPackageVersionResourceEntity entity) {
		try {
			// Yes, that's a SQL N+1 query here, but we can live with it.
			final IBaseBinary binary = this.binaryDao.readByPid(entity.getResourceBinary().getId());
			final byte[] content = this.myBinaryStorageSvc.fetchDataByteArrayFromBinary(binary);
			final IBaseResource resource = FhirContext.forCached(entity.getFhirVersion())
					.newJsonParser()
					.parseResource(new String(content, StandardCharsets.UTF_8));
			return this.matchboxJpaPackageCache.buildInstalledStructureDefinitionEntity(entity, resource);
		} catch (final Exception e) {
			LOG.error(
					"MATCHBOX: failed to backfill MB_INSTALLED_STRUCT_DEF for NpmPackageVersionResourceEntity#{}",
					entity.getId(), e);
			return null;
		}
	}

	/**
	 * Saves a page's worth of rows.
	 * <p>
	 * Fast path: the whole page is saved as a single batch, in a single transaction/commit - this is what almost
	 * every page will hit, since bad rows should be rare. Only if that batch fails does this fall back to
	 * retrying each row individually, each in its own {@code REQUIRES_NEW} transaction: on Postgres, a single
	 * failed statement poisons every subsequent statement on the same connection/transaction until it's rolled
	 * back, so re-running the page one row at a time - each on its own connection - is the only way to identify
	 * and skip just the bad row(s) without aborting the rest of the page. Paying that per-row transaction
	 * overhead (a fresh connection plus a full commit round-trip each) only on the rare page that actually
	 * contains a bad row, instead of on every row, is what keeps the common case fast.
	 */
	private void saveEntities(final List<MbInstalledStructureDefinitionEntity> entities) {
		try {
			this.pageTxTemplate.executeWithoutResult(
					status -> this.installedStructureDefinitionRepository.saveAll(entities));
			return;
		} catch (final Exception e) {
			LOG.debug("MATCHBOX: batch save of {} MB_INSTALLED_STRUCT_DEF rows failed, retrying them individually",
					entities.size(), e);
		}
		for (final MbInstalledStructureDefinitionEntity entity : entities) {
			try {
				this.entityTxTemplate.executeWithoutResult(
						status -> this.installedStructureDefinitionRepository.save(entity));
			} catch (final Exception e) {
				LOG.error("MATCHBOX: failed to save MB_INSTALLED_STRUCT_DEF row for canonical URL '{}'",
						entity.getCanonicalUrl(), e);
			}
		}
	}

	/**
	 * The entities rebuilt from one page, ready to be saved, and whether there is a next page to process.
	 */
	private record PageBuild(List<MbInstalledStructureDefinitionEntity> entities, boolean hasNext) {
	}
}
