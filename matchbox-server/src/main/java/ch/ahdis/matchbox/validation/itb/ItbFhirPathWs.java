package ch.ahdis.matchbox.validation.itb;

import ca.uhn.fhir.rest.api.EncodingEnum;
import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.util.MatchboxEngineSupport;
import com.gitb.model.core.TypedParameter;
import com.gitb.model.ps.ProcessResponse;
import org.hl7.fhir.exceptions.FHIRException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * The ITB (GITB REST) processing service {@code FHIRPathProcessor}: evaluates a FHIRPath expression on a FHIR
 * resource, with the engine of the FHIR version of the server.
 * <p>
 * The result is the one of the HL7 validator's ITB service: the values as text, those of a collection separated by a
 * comma, an empty collection as an empty text.
 * <p>
 * See https://github.com/ahdis/matchbox/issues/681.
 */
@RestController
@RequestMapping(path = "/itb/fhirPath")
public class ItbFhirPathWs extends ItbProcessingWs {

	static final String INPUT_CONTENT = "content";
	static final String INPUT_CONTENT_TYPE = "contentType";
	static final String INPUT_EXPRESSION = "expression";
	static final String OUTPUT_RESULT = "result";

	private final MatchboxEngineSupport matchboxEngineSupport;

	// The base CLI context, with the default parameters
	private final CliContext baseCliContext;

	public ItbFhirPathWs(final MatchboxEngineSupport matchboxEngineSupport, final CliContext baseCliContext) {
		super("FHIRPathProcessor", "matchbox FHIRPath processor",
				"Evaluates a FHIRPath expression on a FHIR resource (JSON or XML).", "evaluate");
		this.matchboxEngineSupport = Objects.requireNonNull(matchboxEngineSupport);
		this.baseCliContext = Objects.requireNonNull(baseCliContext);
	}

	@Override
	protected List<TypedParameter> getInputs() {
		return List.of(
			parameter(INPUT_CONTENT, true, "The FHIR resource the expression is evaluated on, JSON or XML."),
			parameter(INPUT_EXPRESSION, true, "The FHIRPath expression."),
			parameter(INPUT_CONTENT_TYPE, false,
						 "application/fhir+json or application/fhir+xml. Detected from the content if missing."));
	}

	@Override
	protected List<TypedParameter> getOutputs() {
		return List.of(parameter(OUTPUT_RESULT, true,
										 "The result as text, the values of a collection separated by a comma."));
	}

	@Override
	protected ProcessResponse doProcess(final ItbInputs inputs) {
		final String content = inputs.require(INPUT_CONTENT);
		final String expression = inputs.require(INPUT_EXPRESSION);
		final EncodingEnum encoding = ItbValidationWs.getEncoding(inputs.optional(INPUT_CONTENT_TYPE), content);
		if (encoding != EncodingEnum.JSON && encoding != EncodingEnum.XML) {
			throw new ItbBadRequestException("The input '%s' is neither JSON nor XML".formatted(INPUT_CONTENT));
		}

		// The engine of the FHIR version of the server, without an implementation guide
		final MatchboxEngine engine = this.matchboxEngineSupport.getMatchboxEngine(null,
																											new CliContext(this.baseCliContext),
																											true,
																											false);
		if (engine == null) {
			throw new IllegalStateException("The engine of the server is not available");
		}

		final String result;
		try {
			result = engine.evaluateFhirPath(content, encoding == EncodingEnum.JSON, expression);
		} catch (final FHIRException | IOException e) {
			// The expression or the content is not valid
			throw new ItbBadRequestException("FHIRPath evaluation failed: " + e.getMessage());
		}
		return ProcessResponse.builder()
			.withOutput(ItbTarMapper.contextItem(OUTPUT_RESULT, Objects.requireNonNullElse(result, ""), "text/plain", true))
			.build();
	}
}
