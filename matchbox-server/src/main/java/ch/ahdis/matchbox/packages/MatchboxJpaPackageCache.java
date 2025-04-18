package ch.ahdis.matchbox.packages;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.dao.data.MbInstalledStructureDefinitionRepository;
import ca.uhn.fhir.jpa.model.entity.MbInstalledStructureDefinitionEntity;
import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionResourceEntity;
import ca.uhn.fhir.util.FhirTerser;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Service;

/**
 * A service to help Matchbox customizing the JPA
 *
 * @author Quentin Ligier
 * @see <a href="https://github.com/ahdis/matchbox/issues/341">The NpmPackageVersionResourceEntity update is costly</a>
 **/
@Service
public class MatchboxJpaPackageCache {

	private final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository;

	public MatchboxJpaPackageCache(final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository) {
		this.installedStructureDefinitionRepository = installedStructureDefinitionRepository;
	}

	/**
	 * This is Matchbox's hook to intercept the {@link NpmPackageVersionResourceEntity}s being generated when loading a
	 * package right before it gets saved in the database.
	 * <p>
	 * This will check the resource type and delegate to the appropriate method to customize the entity.
	 */
	public void interceptEntityBeforeSaving(final NpmPackageVersionResourceEntity entity,
														 final IBaseResource res) {
		switch (res) {
			case org.hl7.fhir.r4.model.StructureDefinition sdR4 ->
				this.interceptStructureDefinition(entity, sdR4, null, null);
			case org.hl7.fhir.r4b.model.StructureDefinition sdR4b ->
				this.interceptStructureDefinition(entity, null, sdR4b, null);
			case org.hl7.fhir.r5.model.StructureDefinition sdR5 ->
				this.interceptStructureDefinition(entity, null, null, sdR5);
			case org.hl7.fhir.r4.model.StructureMap smR4 -> this.updateStructureMap(entity, smR4, null, null);
			case org.hl7.fhir.r4b.model.StructureMap smR4b -> this.updateStructureMap(entity, null, smR4b, null);
			case org.hl7.fhir.r5.model.StructureMap smR5 -> this.updateStructureMap(entity, null, null, smR5);
			default -> { /* do nothing */ }
		}
	}
	/**
	 * This is Matchbox's hook to intercept the {@link NpmPackageVersionResourceEntity}s being generated when loading a
	 * package right after it gets saved in the database.
	 * <p>
	 * This will check the resource type and delegate to the appropriate method to customize the entity.
	 */
	public void interceptEntityAfterSaving(final NpmPackageVersionResourceEntity entity,
														 final IBaseResource res) {
		switch (res) {
			case org.hl7.fhir.r4.model.StructureDefinition sdR4 ->
				this.interceptStructureDefinition(entity, sdR4, null, null);
			case org.hl7.fhir.r4b.model.StructureDefinition sdR4b ->
				this.interceptStructureDefinition(entity, null, sdR4b, null);
			case org.hl7.fhir.r5.model.StructureDefinition sdR5 ->
				this.interceptStructureDefinition(entity, null, null, sdR5);
			default -> { /* do nothing */ }
		}
	}

	/**
	 * Intercept a StructureDefinition before it gets saved in the database. Create our
	 * MbInstalledStructureDefinitionEntity to store it in an optimized way.
	 */
	private void interceptStructureDefinition(final NpmPackageVersionResourceEntity npmPackageVersionResourceEntity,
															final org.hl7.fhir.r4.model.@Nullable StructureDefinition sdR4,
															final org.hl7.fhir.r4b.model.@Nullable StructureDefinition sdR4b,
															final org.hl7.fhir.r5.model.@Nullable StructureDefinition sdR5) {
		// 1. Modify the original entity
		//    We update the canonical version to the package version for StructureDefinitions
		//    https://github.com/ahdis/matchbox/issues/225
		npmPackageVersionResourceEntity.setCanonicalVersion(npmPackageVersionResourceEntity.getPackageVersion().getVersionId());

		// 2. Extract interesting info
		final var terser = new FhirTerserWrapper(sdR4, sdR4b, sdR5);
		var title = terser.getSinglePrimitiveValueOrNull("title");
		if (title == null) {
			title = terser.getSinglePrimitiveValueOrNull("name");
		}
		final var type = terser.getSinglePrimitiveValueOrNull("type");
		final var kind = terser.getSinglePrimitiveValueOrNull("kind");
		final var isValidatable = !"primitive-type".equals(kind)
			&& !"complex-type".equals(kind)
			&& !"logical".equals(kind)
			&& !"Extension".equals(type);

		// 3. Create our own entity for the StructureDefinition
		final var entity = new MbInstalledStructureDefinitionEntity();
		entity.setCanonicalUrl(npmPackageVersionResourceEntity.getCanonicalUrl());
		entity.setTitle(title);
		entity.setPackageId(npmPackageVersionResourceEntity.getPackageVersion().getPackageId());
		entity.setPackageVersion(npmPackageVersionResourceEntity.getPackageVersion().getVersionId());
		entity.setType(type);
		entity.setKind(kind);
		entity.setCurrent(npmPackageVersionResourceEntity.getPackageVersion().isCurrentVersion());
		entity.setValidatable(isValidatable);
		entity.setNpmPackageVersionResourceEntity(npmPackageVersionResourceEntity);
		this.installedStructureDefinitionRepository.save(entity);
	}

	/**
	 * Updates the NpmPackageVersionResourceEntity of a StructureMap:
	 * <ol>
	 *    <li>entity.myFilename now contains the StructureMap.title or StructureMap.name</li>
	 * </ol>
	 */
	private void updateStructureMap(final NpmPackageVersionResourceEntity npmPackageVersionResourceEntity,
											  final org.hl7.fhir.r4.model.@Nullable StructureMap smR4,
											  final org.hl7.fhir.r4b.model.@Nullable StructureMap smR4b,
											  final org.hl7.fhir.r5.model.@Nullable StructureMap smR5) {
		final var terser = new FhirTerserWrapper(smR4, smR4b, smR5);

		// Change the filename for the StructureDefinition title
		npmPackageVersionResourceEntity.setFilename(terser.getSinglePrimitiveValueOrNull("title"));
	}

	// A small wrapper around FhirTerser to handle the different FHIR versions of a resource
	private static class FhirTerserWrapper {
		private final IBase resource;
		private final FhirTerser terser;

		public FhirTerserWrapper(final org.hl7.fhir.r4.model.@Nullable BaseResource resourceR4,
										 final org.hl7.fhir.r4b.model.@Nullable BaseResource resourceR4b,
										 final org.hl7.fhir.r5.model.@Nullable BaseResource resourceR5) {
			if (resourceR4 != null) {
				this.resource = resourceR4;
				this.terser = new FhirTerser(FhirContext.forR4Cached());
			} else if (resourceR4b != null) {
				this.resource = resourceR4b;
				this.terser = new FhirTerser(FhirContext.forR4BCached());
			} else if (resourceR5 != null) {
				this.resource = resourceR5;
				this.terser = new FhirTerser(FhirContext.forR5Cached());
			} else {
				throw new IllegalArgumentException("All arguments are null");
			}
		}

		public String getSinglePrimitiveValueOrNull(final String thePath) {
			return this.terser.getSinglePrimitiveValueOrNull(this.resource, thePath);
		}
	}
}
