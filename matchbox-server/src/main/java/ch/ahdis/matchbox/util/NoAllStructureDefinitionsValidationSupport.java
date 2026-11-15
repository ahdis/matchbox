package ch.ahdis.matchbox.util;

import java.util.Collections;
import java.util.List;

import org.hl7.fhir.common.hapi.validation.support.BaseValidationSupportWrapper;
import org.hl7.fhir.instance.model.api.IBaseResource;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;

/**
 * Wraps the validation support of the HAPI FhirContext for the FHIRPath engine of the JPA search parameter extractors.
 * <p>
 * The FHIRPathEngine constructor lists all StructureDefinitions for its static type analysis, which isn't used to
 * extract the search parameter values. This makes the HAPI DefaultProfileValidationSupport parse and keep all
 * StructureDefinitions of the FHIR core (about 40 MB for R4) at startup, although matchbox validates with its own
 * engine. The evaluation of the search parameter expressions only fetches single type definitions (e.g. for 'is' and
 * 'as'), which are still delegated.
 */
public class NoAllStructureDefinitionsValidationSupport extends BaseValidationSupportWrapper {

	public NoAllStructureDefinitionsValidationSupport(final FhirContext fhirContext, final IValidationSupport wrap) {
		super(fhirContext, wrap);
	}

	@Override
	public <T extends IBaseResource> List<T> fetchAllStructureDefinitions() {
		return Collections.emptyList();
	}

	@Override
	public String getName() {
		return "NoAllStructureDefinitionsValidationSupport";
	}
}
