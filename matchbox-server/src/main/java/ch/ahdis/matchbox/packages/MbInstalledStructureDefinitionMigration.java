package ch.ahdis.matchbox.packages;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.binary.api.IBinaryStorageSvc;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionResourceDao;
import ca.uhn.fhir.jpa.dao.data.MbInstalledStructureDefinitionRepository;
import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionResourceEntity;
import ch.ahdis.matchbox.config.MatchboxJpaConfig;
import ch.ahdis.matchbox.spring.MatchboxEventListener;
import org.hl7.fhir.instance.model.api.IBaseBinary;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

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
 * This runs as an {@link ApplicationRunner} so that it completes before {@link MatchboxEventListener}
 * starts installing the configured ImplementationGuides.
 * <p>
 * The bean is registered with prototype scope (see {@link MatchboxJpaConfig}) since it only does one-time startup
 * work: there won't be any reference kept to it, and it'll be collected by GC during the app lifecycle.
 *
 * @author Quentin Ligier
 **/
public class MbInstalledStructureDefinitionMigration implements ApplicationRunner {
	private static final Logger LOG = LoggerFactory.getLogger(MbInstalledStructureDefinitionMigration.class);

	private final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository;
	private final MatchboxJpaPackageCache matchboxJpaPackageCache;
	private final INpmPackageVersionResourceDao myPackageVersionResourceDao;
	private final IBinaryStorageSvc myBinaryStorageSvc;
	private final IFhirResourceDao<IBaseBinary> binaryDao;

	public MbInstalledStructureDefinitionMigration(final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository,
																  final MatchboxJpaPackageCache matchboxJpaPackageCache,
																  final INpmPackageVersionResourceDao myPackageVersionResourceDao,
																  final IBinaryStorageSvc myBinaryStorageSvc,
																  final DaoRegistry myDaoRegistry) {
		this.installedStructureDefinitionRepository = requireNonNull(installedStructureDefinitionRepository);
		this.matchboxJpaPackageCache = requireNonNull(matchboxJpaPackageCache);
		this.myPackageVersionResourceDao = requireNonNull(myPackageVersionResourceDao);
		this.myBinaryStorageSvc = requireNonNull(myBinaryStorageSvc);
		this.binaryDao = (IFhirResourceDao<IBaseBinary>) myDaoRegistry.getResourceDao("Binary");
	}

	@Override
	@Transactional
	public void run(final ApplicationArguments args) {
		if (this.installedStructureDefinitionRepository.count() > 0) {
			// Either this is not the first startup after the upgrade, or packages have already been installed
			// (e.g. igsPreloaded) and went through the normal, up-to-date hook. Nothing to backfill.
			return;
		}
		LOG.info("MB_INSTALLED_STRUCT_DEF is empty, backfilling it from the already-installed StructureDefinitions");
		this.backfillInstalledStructureDefinitions();
	}

	/**
	 * Backfill for the MB_INSTALLED_STRUCT_DEF table on trigger
	 * <p>
	 * That table is populated by {@link MatchboxJpaPackageCache#interceptEntityAfterSaving} whenever a
	 * StructureDefinition is freshly installed. On an upgrade from a version predating that table, existing
	 * StructureDefinitions were never processed by that hook, so this replays it for every StructureDefinition
	 * already persisted in NPM_PACKAGE_VER_RES.
	 * <p>
	 * Reprocesses every installed StructureDefinition unconditionally, so callers should only invoke this once,
	 * when MB_INSTALLED_STRUCT_DEF is empty (see {@link MbInstalledStructureDefinitionMigration}).
	 */
	private void backfillInstalledStructureDefinitions() {
		Pageable page = PageRequest.of(0, 50);
		Slice<NpmPackageVersionResourceEntity> slice;
		do {
			slice = this.myPackageVersionResourceDao.findByResourceType(page, "StructureDefinition");
			for (final NpmPackageVersionResourceEntity entity : slice.getContent()) {
				try {
					// Yes, that's a SQL N+1 query here, but we can live with it.
					final IBaseBinary binary = this.binaryDao.readByPid(entity.getResourceBinary().getId());
					final byte[] content = this.myBinaryStorageSvc.fetchDataByteArrayFromBinary(binary);
					final IBaseResource resource = FhirContext.forCached(entity.getFhirVersion())
						.newJsonParser()
						.parseResource(new String(content, StandardCharsets.UTF_8));
					this.matchboxJpaPackageCache.interceptEntityAfterSaving(entity, resource);
				} catch (final Exception e) {
					LOG.error(
						"MATCHBOX: failed to backfill MB_INSTALLED_STRUCT_DEF for NpmPackageVersionResourceEntity#{}",
						entity.getId(), e);
				}
			}
			page = page.next();
		} while (slice.hasNext());
	}
}
