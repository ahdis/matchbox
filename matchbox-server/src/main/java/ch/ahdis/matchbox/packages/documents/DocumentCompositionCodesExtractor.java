package ch.ahdis.matchbox.packages.documents;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.binary.api.IBinaryStorageSvc;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionResourceDao;
import ca.uhn.fhir.jpa.dao.data.MbInstalledStructureDefinitionRepository;
import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionResourceEntity;
import ch.ahdis.matchbox.util.CrossVersionResourceUtils;
import ch.ahdis.matchbox.util.MatchboxServerUtils;
import ch.ahdis.matchbox.util.http.MatchboxFhirFormat;
import jakarta.annotation.Nullable;
import org.hl7.fhir.instance.model.api.IBaseBinary;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.ElementDefinition;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * An extractor of the type and category codes from a FHIR document Composition.
 */
@Service
public class DocumentCompositionCodesExtractor {
	private static final Logger log = LoggerFactory.getLogger(DocumentCompositionCodesExtractor.class);

	/**
	 * How many candidate resources to look at when resolving a canonical URL against the installed
	 * StructureDefinitions: several package versions can share the same canonical, but we only need the first one
	 * that actually is a StructureDefinition.
	 */
	private static final int CANONICAL_LOOKUP_PAGE_SIZE = 10;

	private final DaoRegistry myDaoRegistry;
	private final IBinaryStorageSvc myBinaryStorageSvc;
	private final INpmPackageVersionResourceDao myPackageVersionResourceDao;
	private final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository;

	public DocumentCompositionCodesExtractor(final DaoRegistry myDaoRegistry,
														  final IBinaryStorageSvc myBinaryStorageSvc,
														  final INpmPackageVersionResourceDao myPackageVersionResourceDao,
														  final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository) {
		this.myDaoRegistry = myDaoRegistry;
		this.myBinaryStorageSvc = myBinaryStorageSvc;
		this.myPackageVersionResourceDao = myPackageVersionResourceDao;
		this.installedStructureDefinitionRepository = installedStructureDefinitionRepository;
	}

	@Nullable
	public DocumentCompositionCodes extractCodes(final NpmPackageVersionResourceEntity npmPackageVersionResourceEntity) {
		final var bundleSD = this.getStructureDefinitionAsR5(npmPackageVersionResourceEntity);
		if (bundleSD == null) {
			log.trace("The entity '{}' isn't a StructureDefinition", npmPackageVersionResourceEntity.getCanonicalUrl());
			return null;
		}

		final var compositionCanonical = this.extractCompositionCanonical(bundleSD);
		if (compositionCanonical == null) {
			log.trace("The Bundle StructureDefinition '{}' doesn't link to a Composition profile",
						 npmPackageVersionResourceEntity.getCanonicalUrl());
			return null;
		}
		final var compositionSD = this.getCompositionStructureDefinitionAsR5(compositionCanonical);
		if (compositionSD == null) {
			log.trace("The Composition StructureDefinition '{}' can't be found", compositionCanonical);
			return null;
		}

		final var typeCode = extractFixedCodingAsCode(compositionSD, "Composition.type");
		final var categoryCode = extractFixedCodingAsCode(compositionSD, "Composition.category");
		if (typeCode != null || categoryCode != null) {
			log.debug("The Composition StructureDefinition '{}' has fixed codes: typeCode={}, categoryCode={}",
						 typeCode,
						 categoryCode,
						 compositionCanonical);
			return new DocumentCompositionCodes(typeCode, categoryCode);
		}
		log.trace("The Composition StructureDefinition '{}' doesn't have fixed type or category codes", compositionCanonical);
		return null;
	}

	/**
	 * Returns the StructureDefinition of the given entity as a R5 StructureDefinition if it's a StructureDefinition
	 * of any supported FHIR version, null otherwise.
	 */
	@Nullable
	private StructureDefinition getStructureDefinitionAsR5(final NpmPackageVersionResourceEntity npmPackageVersionResourceEntity) {
		if (!"StructureDefinition".equals(npmPackageVersionResourceEntity.getResourceType())) {
			return null;
		}

		final FhirVersionEnum fhirVersion = npmPackageVersionResourceEntity.getFhirVersion();
		final byte[] content;
		try {
			final IBaseBinary binary = MatchboxServerUtils.getBinaryFromId(
					npmPackageVersionResourceEntity.getResourceBinary().getId(),
					this.myDaoRegistry
			);
			content = MatchboxServerUtils.fetchBlobFromBinary(binary,
																			  this.myBinaryStorageSvc,
																			  FhirContext.forCached(fhirVersion));
		} catch (final Exception e) {
			log.warn("Failed to read the content of the StructureDefinition '{}'",
					npmPackageVersionResourceEntity.getCanonicalUrl(), e);
			return null;
		}

		final org.hl7.fhir.r5.model.Resource resource;
		try {
			resource = switch (fhirVersion) {
				case R4 -> CrossVersionResourceUtils.parseR4AsR5(content, MatchboxFhirFormat.JSON);
				case R4B -> CrossVersionResourceUtils.parseR4bAsR5(content, MatchboxFhirFormat.JSON);
				case R5 -> CrossVersionResourceUtils.parseR5(content, MatchboxFhirFormat.JSON);
				default -> {
					log.warn("Unsupported FHIR version '{}' for StructureDefinition '{}'",
							fhirVersion, npmPackageVersionResourceEntity.getCanonicalUrl());
					yield null;
				}
			};
		} catch (final Exception e) {
			log.warn("Failed to parse the StructureDefinition '{}'",
					npmPackageVersionResourceEntity.getCanonicalUrl(), e);
			return null;
		}

		return resource instanceof StructureDefinition sd ? sd : null;
	}

	/**
	 * Looks into the StructureDefinition of a document-type Bundle and extracts the canonical of the Composition
	 * resource, if it's fixed.
	 * If the canonical is not fixed to a single value or can't be found, it will return null.
	 */
	@Nullable
	public String extractCompositionCanonical(final StructureDefinition bundleSD) {
		for (final ElementDefinition element : elementsOf(bundleSD)) {
			if (!"Bundle.entry.resource".equals(element.getPath())) {
				continue;
			}
			for (final ElementDefinition.TypeRefComponent type : element.getType()) {
				if (type.getProfile().size() != 1) {
					continue;
				}
				final var canonical = type.getProfile().getFirst().getValue();
				switch (type.getCode()) {
					case "Composition" -> {
						// The StructureDefinition indicates that the slice is a Composition, it's the easy case.
						return canonical;
					}
					case "Resource" -> {
						// The StructureDefinition doesn't indicate the resource type, we need to query the database to find
						// out if the profile is a Composition or not.
						if (this.installedStructureDefinitionRepository.existsByCanonicalAndType(canonical,
																														 "Composition")) {
							return canonical;
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * Searches for the given canonical in the installed StructureDefinitions and returns it as a R5
	 * StructureDefinition if found.
	 */
	@Nullable
	public StructureDefinition getCompositionStructureDefinitionAsR5(final String compositionCanonical) {
		final List<NpmPackageVersionResourceEntity> candidates = this.myPackageVersionResourceDao
				.findCurrentVersionByCanonicalUrl(PageRequest.of(0, CANONICAL_LOOKUP_PAGE_SIZE), compositionCanonical)
				.getContent();
		for (final NpmPackageVersionResourceEntity candidate : candidates) {
			if (!"StructureDefinition".equals(candidate.getResourceType())) {
				continue;
			}
			final var sd = this.getStructureDefinitionAsR5(candidate);
			if (sd != null) {
				return sd;
			}
		}
		return null;
	}

	/**
	 * Looks for an element at the given path with a fixed or pattern CodeableConcept holding exactly one Coding
	 * (with both a system and a code), and returns it as {@code system#code}. Returns null if the element doesn't
	 * exist, or its fixed/pattern value doesn't resolve to exactly one fully-specified Coding.
	 */
	@Nullable
	private static String extractFixedCodingAsCode(final StructureDefinition sd, final String path) {
		final var codingPath = path + ".coding";
		for (final ElementDefinition element : elementsOf(sd)) {
			if (!element.hasPattern()) {
				continue;
			}
			Coding coding = null;

			if (path.equals(element.getPath()) && element.getPattern() instanceof CodeableConcept patternCC) {
				// Check if the CodeableConcept has a pattern
				if (patternCC.getCoding().size() == 1) {
					coding = patternCC.getCodingFirstRep();
				}
			} else if (codingPath.equals(element.getPath()) && element.getPattern() instanceof Coding patternCoding) {
				// Check if the Coding inside the CodeableConcept has a pattern
				coding = patternCoding;
			}

			if (coding != null && coding.hasSystem() && coding.hasCode()) {
				return coding.getSystem() + "#" + coding.getCode();
			}
		}
		return null;
	}

	/**
	 * Returns the snapshot elements of a StructureDefinition, falling back to the differential ones if no snapshot
	 * was generated.
	 */
	private static List<ElementDefinition> elementsOf(final StructureDefinition sd) {
		if (sd.hasSnapshot() && !sd.getSnapshot().getElement().isEmpty()) {
			return sd.getSnapshot().getElement();
		}
		return sd.getDifferential().getElement();
	}

	public record DocumentCompositionCodes(String typeCode, String categoryCode) {}
}
