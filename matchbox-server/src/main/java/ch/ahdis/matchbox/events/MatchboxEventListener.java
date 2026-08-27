package ch.ahdis.matchbox.events;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.binary.api.IBinaryStorageSvc;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionResourceDao;
import ca.uhn.fhir.jpa.dao.data.MbInstalledStructureDefinitionRepository;
import ch.ahdis.matchbox.packages.MatchboxImplementationGuideProvider;
import ch.ahdis.matchbox.packages.MatchboxJpaPackageCache;
import ch.ahdis.matchbox.packages.documents.DocumentCompositionCodesExtractor;
import ch.ahdis.matchbox.packages.migrations.MbInstalledStructureDefinitionV1Migration;
import ch.ahdis.matchbox.packages.migrations.MbInstalledStructureDefinitionV2Migration;
import ch.ahdis.matchbox.util.MatchboxEngineSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listener for Matchbox events, as per the Spring Boot event system.
 */
public class MatchboxEventListener {
	private static final Logger LOGGER = LoggerFactory.getLogger(MatchboxEventListener.class);

	private final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository;
	private final DaoRegistry myDaoRegistry;
	private final IBinaryStorageSvc myBinaryStorageSvc;
	private final INpmPackageVersionResourceDao myPackageVersionResourceDao;
	private final MatchboxImplementationGuideProvider igProvider;
	private final PlatformTransactionManager txManager;
	private final MatchboxJpaPackageCache matchboxJpaPackageCache;
	private final MatchboxEngineSupport matchboxEngineSupport;

	public MatchboxEventListener(final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository,
										  final DaoRegistry myDaoRegistry,
	                             final IBinaryStorageSvc myBinaryStorageSvc,
	                             final INpmPackageVersionResourceDao myPackageVersionResourceDao,
	                             final MatchboxImplementationGuideProvider igProvider,
	                             final PlatformTransactionManager txManager,
	                             final MatchboxJpaPackageCache matchboxJpaPackageCache,
	                             final MatchboxEngineSupport matchboxEngineSupport) {
		this.installedStructureDefinitionRepository = installedStructureDefinitionRepository;
		this.myDaoRegistry = myDaoRegistry;
		this.myBinaryStorageSvc = myBinaryStorageSvc;
		this.myPackageVersionResourceDao = myPackageVersionResourceDao;
		this.igProvider = igProvider;
		this.txManager = txManager;
		this.matchboxJpaPackageCache = matchboxJpaPackageCache;
		this.matchboxEngineSupport = matchboxEngineSupport;
	}

	/**
	 * Application ready event: load all ImplementationGuides specified in the app configuration.
	 */
	@EventListener
	public void handleApplicationReadyEvent(final ApplicationReadyEvent ignored) {
		LOGGER.debug("Loading all ImplementationGuides");
		this.igProvider.loadAll(false);
		this.migrateMatchboxData();
	}

	/**
	 * ImplementationGuide(s) installed event: run the post-processing of the installed StructureDefinitions.
	 */
	@EventListener
	@Transactional
	public void handleImplementationGuideInstalledEvent(final ImplementationGuideInstalledEvent ignored) {
		LOGGER.debug("Received an ImplementationGuideInstalledEvent");
		final var extractor = new DocumentCompositionCodesExtractor(this.myDaoRegistry,
		                                                            this.myBinaryStorageSvc,
		                                                            this.myPackageVersionResourceDao,
																						this.installedStructureDefinitionRepository);
		final var entities = this.installedStructureDefinitionRepository.findAllForDocumentBundleProcessing();
		for (final var entity : entities) {
			try {
				final var codes = extractor.extractCodes(entity.getNpmPackageVersionResourceEntity());
				if (codes != null) {
					entity.setDocCompTypeCode(codes.typeCode());
					entity.setDocCompCatCode(codes.categoryCode());
				} else {
					entity.setDocCompTypeCode(null);
				}
				this.installedStructureDefinitionRepository.save(entity);
			} catch (final Exception e) {
				LOGGER.warn("Failed to extract document type/category codes from StructureDefinition '{}'",
				            entity.getCanonicalUrl(), e);
			}
		}
		LOGGER.trace("Done processing the ImplementationGuideInstalledEvent");
	}

	/**
	 * An ImplementationGuide has been uninstalled: remove all relevant engines from cache.
	 */
	@EventListener
	public void handleImplementationGuideUninstalledEvent(final ImplementationGuideUninstalledEvent ignored) {
		LOGGER.debug("Received an ImplementationGuideUninstalledEvent");
		this.matchboxEngineSupport.onImplementationGuideUninstalled(ignored.getPackageId(), ignored.getPackageVersion());
	}

	/**
	 * Performs data migration for Matchbox data, stored in the {$link MbInstalledStructureDefinitionRepository}.
	 */
	private void migrateMatchboxData() {
		// Migrate to meta version 1
		// This migration fills the MB_INSTALLED_STRUCT_DEF table with entries for all StructureDefinitions that have
		// already been installed.
		if (this.installedStructureDefinitionRepository.count() == 0) {
			new MbInstalledStructureDefinitionV1Migration(this.installedStructureDefinitionRepository,
			                                              this.matchboxJpaPackageCache,
			                                              this.myPackageVersionResourceDao,
			                                              this.myBinaryStorageSvc,
			                                              this.myDaoRegistry,
			                                              this.txManager).run();
		}

		// Migrate to meta version 2
		if (this.installedStructureDefinitionRepository.existsByMetaVersion((byte) 1)) {
			LOGGER.info("Migrating Matchbox data to version 2");
			new MbInstalledStructureDefinitionV2Migration(this.installedStructureDefinitionRepository,
			                                              this.myDaoRegistry,
			                                              this.myBinaryStorageSvc,
			                                              this.myPackageVersionResourceDao,
			                                              this.txManager).run();
		}
	}
}
