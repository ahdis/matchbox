package ch.ahdis.matchbox.providers;

import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.jpa.dao.data.MbInstalledStructureDefinitionRepository;
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
import java.util.Optional;
import java.util.UUID;

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
		if (bundle.getType() != Bundle.BundleType.DOCUMENT) {
			wrapper.writeResponse(response);
			return;
		}
		final var composition = Optional.of(bundle.getEntryFirstRep())
			.map(Bundle.BundleEntryComponent::getResource)
			.filter(Composition.class::isInstance)
			.map(Composition.class::cast)
			.orElse(null);
		if (composition == null) {
			wrapper.writeResponse(response);
			return;
		}
		final var typeCoding = composition.getType().getCodingFirstRep();
		final var categoryCoding = composition.getCategoryFirstRep().getCodingFirstRep();
		response.addParameter("composition-type", composition.getType());
		for (final var category : composition.getCategory()) {
			response.addParameter("composition-category", category);
		}
		final var typeCode = "%s#%s".formatted(nullToEmpty(typeCoding.getSystem()), nullToEmpty(typeCoding.getCode()));
		final var categoryCode = "%s#%s".formatted(nullToEmpty(categoryCoding.getSystem()),
																 nullToEmpty(categoryCoding.getCode()));
		final var entities = this.installedStructureDefinitionRepository.findAllByDocumentTypeAndCategory(typeCode, categoryCode);
		for (final var entity : entities) {
			response.addParameter("profile", new CanonicalType(entity.getCanonicalUrl()));
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
			parameter.addExtension("composition-category", new UriType(entity.getDocCompCatCode()));
		}
		return this.fhirVersion.convertForResponse(response);
	}

	private String nullToEmpty(@Nullable final String value) {
		return value == null ? "" : value;
	}
}
