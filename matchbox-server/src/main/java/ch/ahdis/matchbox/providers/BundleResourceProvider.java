package ch.ahdis.matchbox.providers;

import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.jpa.dao.data.MbInstalledStructureDefinitionRepository;
import ca.uhn.fhir.jpa.model.entity.MbInstalledStructureDefinitionEntity;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ch.ahdis.matchbox.config.MatchboxFhirVersion;
import ch.ahdis.matchbox.util.http.HttpRequestWrapper;
import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.*;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static ch.ahdis.matchbox.packages.documents.DocumentCompositionCodesExtractor.serializeCoding;

public class BundleResourceProvider extends AbstractMatchboxResourceProvider {

	private final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository;

	private final FhirVersionEnum serverFhirVersion;

	public BundleResourceProvider(final MatchboxFhirVersion fhirVersion,
	                              final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository,
	                              @Value("${hapi.fhir.fhir_version}") final FhirVersionEnum serverFhirVersion) {
		super(fhirVersion,
		      org.hl7.fhir.r4.model.Bundle.class,
		      org.hl7.fhir.r4b.model.Bundle.class,
		      org.hl7.fhir.r5.model.Bundle.class);
		this.installedStructureDefinitionRepository = installedStructureDefinitionRepository;
		this.serverFhirVersion = serverFhirVersion;
	}

	/**
	 * An operation that takes a Bundle resource (of type document), and returns a list of potential StructureDefinition
	 * profiles for it.
	 * It does so by matching the Composition type and category codes to the installed StructureDefinitions, running a
	 * simple comparison of fixed patterns in the Composition definition.
	 */
	@Operation(name = "$get-profiles", idempotent = true, manualResponse = true, manualRequest = true)
	public void getProfiles(final HttpServletRequest theServletRequest,
	                        final HttpServletResponse theServletResponse) throws IOException {
		final var wrapper = new HttpRequestWrapper(theServletRequest, theServletResponse, this.serverFhirVersion);
		final var body = wrapper.parseBodyAsResource();
		final Bundle bundle;
		if (body instanceof final Bundle itsABundle) {
			bundle = itsABundle;
		} else if (body instanceof final Parameters parameters) {
			final var parameter = parameters.getParameter("resource");
			if (parameter != null && parameter.getResource() instanceof final Bundle itsABundle) {
				bundle = itsABundle;
			} else {
				throw new InvalidRequestException("Parameters must contain a 'resource' parameter of type Bundle");
			}
		} else {
			throw new InvalidRequestException("Request body must be a Bundle or Parameters resource");
		}

		final var response = new Parameters();
		response.setId(UUID.randomUUID().toString());

		final var analysis = getProfilesForBundle(bundle);
		if (analysis == null) {
			wrapper.writeResponse(response);
			return;
		}
		response.addParameter("composition-type", analysis.type());
		for (final var category : analysis.categories()) {
			response.addParameter("composition-category", category);
		}
		for (final var profile : analysis.profiles()) {
			final var canonical = new CanonicalType(profile.canonical());
			canonical.addExtension("ig-id", new StringType(profile.igId()));
			canonical.addExtension("ig-version", new StringType(profile.igVersion()));
			canonical.addExtension("ig-current", new BooleanType(profile.igCurrent()));
			canonical.addExtension("sd-canonical", new StringType(profile.canonical()));
			canonical.addExtension("sd-title", new StringType(profile.title()));
			response.addParameter()
				.setName("profile")
				.setValue(canonical);
		}
		wrapper.writeResponse(response);
	}

	/**
	 * A debug operation to list all the StructureDefinitions for document Bundles that are identifiable from their
	 * Composition (in particular the type and category codes).
	 */
	@Operation(name = "$list-recognizable-documents", idempotent = true)
	public IBaseResource listRecognizableDocuments() {
		final var entities = this.installedStructureDefinitionRepository.findAllRecognizableDocuments();
		final var response = new Parameters();
		response.setId(UUID.randomUUID().toString());
		for (final var entity : entities) {
			final var parameter = response.addParameter()
				.setName("profile")
				.setValue(new CanonicalType(entity.getCanonicalUrl()));
			parameter.addExtension("composition-type", new UriType(entity.getDocCompTypeCode()));
			if (entity.getDocCompCatCode() != null) {
				parameter.addExtension("composition-category", new UriType(entity.getDocCompCatCode()));
			}
		}
		return this.fhirVersion.convertForResponse(response);
	}

	@Nullable
	public BundleAnalysis getProfilesForBundle(final Bundle bundle) {
		if (bundle.getType() != Bundle.BundleType.DOCUMENT) {
			return null;
		}
		final var composition = Optional.of(bundle.getEntryFirstRep())
			.map(Bundle.BundleEntryComponent::getResource)
			.filter(Composition.class::isInstance)
			.map(Composition.class::cast)
			.orElse(null);
		if (composition == null) {
			return null;
		}
		final var typeCoding = composition.getType().getCodingFirstRep();
		final var categoryCoding = composition.getCategoryFirstRep().getCodingFirstRep();
		final var typeCode = serializeCoding(typeCoding);
		if (typeCode == null) {
			return null;
		}
		final var categoryCode = serializeCoding(categoryCoding);
		final List<MbInstalledStructureDefinitionEntity> entities;
		if (categoryCode != null) {
			entities = this.installedStructureDefinitionRepository.findAllByDocumentTypeAndCategory(typeCode, categoryCode);
		} else {
			entities = this.installedStructureDefinitionRepository.findAllByDocumentTypeWithoutCategory(typeCode);
		}

		return new BundleAnalysis(
			composition.getType(),
			composition.getCategory(),
			entities.stream()
				.map(entity -> new ValidationProfile(
					entity.getCanonicalUrl(),
					entity.getPackageId(),
					entity.getPackageVersion(),
					entity.isCurrent() != null && entity.isCurrent(),
					entity.getTitle()
				))
				.toList()
		);
	}

	public record BundleAnalysis(
		CodeableConcept type,
		List<CodeableConcept> categories,
		List<ValidationProfile> profiles
	) {}

	public record ValidationProfile(
		String canonical,
		String igId,
		String igVersion,
		boolean igCurrent,
		String title
	) {}
}
