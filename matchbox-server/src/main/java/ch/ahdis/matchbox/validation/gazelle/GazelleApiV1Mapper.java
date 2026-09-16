package ch.ahdis.matchbox.validation.gazelle;

import ch.ahdis.matchbox.validation.gazelle.models.validation.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Reads and writes the models of the Gazelle Validation Service API v1, while the models of this package follow v2.
 * <p>
 * Both versions describe the same things, v2 renamed a few fields and added some others. Instead of maintaining a
 * second set of models, the v1 JSON is produced with Jackson mix-ins:
 * <ul>
 *    <li>request and report: {@code validationItems} (v1) is {@code inputs} (v2);</li>
 *    <li>item: {@code role} (v1) is {@code id} (v2), {@code mimeType} is v2 only;</li>
 *    <li>report: {@code modelVersion} is {@code 0.1};</li>
 *    <li>v2-only fields are omitted: profile {@code version}, {@code standards} and {@code inputs}, method
 *    {@code validationProfileName}, counters {@code numberOfUndefined}, assertion {@code subjectLocations}.</li>
 * </ul>
 *
 * @author Oliver Egger
 **/
public class GazelleApiV1Mapper {

	public static final String MODEL_VERSION = "0.1";

	private final ObjectMapper objectMapper;

	/**
	 * @param baseObjectMapper the mapper used for v2, copied to keep the same date and inclusion settings.
	 */
	public GazelleApiV1Mapper(final ObjectMapper baseObjectMapper) {
		this.objectMapper = baseObjectMapper.copy()
			// v1 requests carry apiVersion and validationServiceName, which matchbox does not need
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.addMixIn(ValidationRequest.class, ValidationRequestV1.class)
			.addMixIn(ValidationReport.class, ValidationReportV1.class)
			.addMixIn(Input.class, InputV1.class)
			.addMixIn(ValidationProfile.class, ValidationProfileV1.class)
			.addMixIn(ValidationMethod.class, ValidationMethodV1.class)
			.addMixIn(ValidationCounters.class, ValidationCountersV1.class)
			.addMixIn(AssertionReport.class, AssertionReportV1.class);
	}

	public ValidationRequest readRequest(final String json) throws JsonProcessingException {
		return this.objectMapper.readValue(json, ValidationRequest.class);
	}

	public String write(final Object value) throws JsonProcessingException {
		if (value instanceof final ValidationReport report) {
			report.setModelVersion(MODEL_VERSION);
		}
		return this.objectMapper.writeValueAsString(value);
	}

	abstract static class ValidationRequestV1 {
		@JsonProperty("validationItems")
		private List<Input> inputs;

		@JsonProperty("validationItems")
		abstract List<Input> getInputs();

		@JsonProperty("validationItems")
		abstract ValidationRequest setInputs(List<Input> inputs);
	}

	abstract static class ValidationReportV1 {
		@JsonProperty("validationItems")
		abstract List<Input> getInputs();

		@JsonProperty("validationItems")
		abstract ValidationReport setInputs(List<Input> inputs);
	}

	@JsonIgnoreProperties({"mimeType"})
	abstract static class InputV1 {
		@JsonProperty("role")
		private String id;

		@JsonProperty("role")
		abstract String getId();

		@JsonProperty("role")
		abstract Input setId(String id);
	}

	@JsonIgnoreProperties({"version", "standards", "inputs"})
	abstract static class ValidationProfileV1 {
	}

	@JsonIgnoreProperties({"validationProfileName"})
	abstract static class ValidationMethodV1 {
	}

	@JsonIgnoreProperties({"numberOfUndefined"})
	abstract static class ValidationCountersV1 {
	}

	@JsonIgnoreProperties({"subjectLocations"})
	abstract static class AssertionReportV1 {
	}
}
