package ch.ahdis.matchbox.packages;

import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionDao;
import ca.uhn.fhir.jpa.dao.data.MbInstalledStructureDefinitionRepository;
import ca.uhn.fhir.jpa.model.entity.MbInstalledStructureDefinitionEntity;
import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionEntity;
import ca.uhn.fhir.jpa.starter.Application;
import ch.ahdis.matchbox.test.CompareUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link MbInstalledStructureDefinitionEntity#isCurrent()} is a live, formula-computed value (joined
 * to NPM_PACKAGE_VER.CURRENT_VERSION) rather than a snapshot frozen at row-creation time.
 *
 * @author Quentin Ligier
 * @see <a href="https://github.com/ahdis/matchbox/issues/341">The NpmPackageVersionResourceEntity update is costly</a>
 **/
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(classes = {Application.class, MbInstalledStructureDefinitionIsCurrentTest.Config.class})
@ActiveProfiles({"tests", "test-r4"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MbInstalledStructureDefinitionIsCurrentTest {

	private static final String PACKAGE_ID = "matchbox.health.test.ig.r4";
	private static final String PACKAGE_VERSION = "0.3.0";

	@Autowired
	private MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository;

	@Autowired
	private PackageVersionFlipper packageVersionFlipper;

	@BeforeAll
	void waitUntilStartup() throws Exception {
		Thread.sleep(10000); // give the server some time to start up
		CompareUtil.logMemory();
	}

	/**
	 * This is the only version of "matchbox.health.test.ig.r4" installed by the test-r4 profile, so it starts out
	 * current. We flip NPM_PACKAGE_VER.CURRENT_VERSION directly (bypassing MatchboxJpaPackageCache entirely, the
	 * same way installing a newer version or uninstalling the current one would) and confirm
	 * MbInstalledStructureDefinitionEntity#isCurrent() picks up the change on its next read, without any write to
	 * MB_INSTALLED_STRUCT_DEF itself.
	 * <p>
	 * The read assertions ({@link #findRowsForTestIg()}) are deliberately called outside of any transaction: each
	 * call gets its own fresh Hibernate session, forcing the {@code @Formula} to actually be recomputed against
	 * the current database state rather than served from a session's first-level cache. The flip-and-save step
	 * goes through {@link #packageVersionFlipper}, a separate {@code @Transactional}-proxied bean, both because
	 * {@link INpmPackageVersionDao} requires an already-open transaction (it's marked
	 * {@code @Transactional(propagation = MANDATORY)}) and to make sure that write is fully committed - via a
	 * distinct transaction - before the next read.
	 */
	@Test
	void isCurrentReflectsTheLiveNpmPackageVerFlagWithoutBeingWrittenTo() {
		final List<MbInstalledStructureDefinitionEntity> before = this.findRowsForTestIg();
		assertFalse(before.isEmpty(), "Sanity check: the test IG should have installed StructureDefinitions");
		assertTrue(before.stream().allMatch(MbInstalledStructureDefinitionEntity::isCurrent),
				"Sanity check: the test IG is the only installed version of its package, so it should start out current");

		try {
			this.packageVersionFlipper.setCurrentVersion(PACKAGE_ID, PACKAGE_VERSION, false);

			final List<MbInstalledStructureDefinitionEntity> afterFlip = this.findRowsForTestIg();
			assertFalse(afterFlip.isEmpty());
			assertFalse(afterFlip.stream().anyMatch(MbInstalledStructureDefinitionEntity::isCurrent),
					"isCurrent should reflect the flipped NPM_PACKAGE_VER.CURRENT_VERSION on the next read, "
							+ "without requiring any write to MB_INSTALLED_STRUCT_DEF itself");
		} finally {
			// Restore the flag, in case this Spring context ever gets reused by another test relying on it (e.g.
			// the Gazelle/CapabilityStatement profile listing tests).
			this.packageVersionFlipper.setCurrentVersion(PACKAGE_ID, PACKAGE_VERSION, true);
		}

		final List<MbInstalledStructureDefinitionEntity> afterRestore = this.findRowsForTestIg();
		assertTrue(afterRestore.stream().allMatch(MbInstalledStructureDefinitionEntity::isCurrent));
	}

	private List<MbInstalledStructureDefinitionEntity> findRowsForTestIg() {
		return this.installedStructureDefinitionRepository.findAllValidatable().stream()
				.filter(entity -> PACKAGE_ID.equals(entity.getPackageId())
						&& PACKAGE_VERSION.equals(entity.getPackageVersion()))
				.toList();
	}

	@TestConfiguration
	static class Config {
		@Bean
		PackageVersionFlipper packageVersionFlipper() {
			return new PackageVersionFlipper();
		}
	}

	/**
	 * A small test-only helper bean, so the flip-and-save below runs through a real, separately-proxied
	 * {@code @Transactional} call (satisfying {@link INpmPackageVersionDao}'s mandatory-transaction requirement,
	 * and committing on its own) instead of sharing a session with the test method's read assertions.
	 */
	static class PackageVersionFlipper {

		@Autowired
		private INpmPackageVersionDao packageVersionDao;

		@Transactional
		public void setCurrentVersion(final String packageId, final String version, final boolean current) {
			final NpmPackageVersionEntity packageVersion = this.packageVersionDao
					.findByPackageIdAndVersion(packageId, version)
					.orElseThrow();
			packageVersion.setCurrentVersion(current);
			this.packageVersionDao.save(packageVersion);
		}
	}
}
