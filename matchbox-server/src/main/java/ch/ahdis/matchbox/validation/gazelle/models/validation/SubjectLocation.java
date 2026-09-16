package ch.ahdis.matchbox.validation.gazelle.models.validation;

/**
 * The location of the subject of an assertion, in one of the inputs (Validation Service API v2).
 * <p>
 * Copy-pasted from
 * https://gitlab.inria.fr/gazelle/public/core/validation-service-api/-/blob/2.2.0/validation-v2-api/src/main/java/net/ihe/gazelle/validation/v2/api/business/report/SubjectLocation.java
 **/
public class SubjectLocation {

	public static final String LINE_COLUMN_TYPE = "line-column";
	public static final String FHIR_PATH_TYPE = "FHIRPath";

	private String type;
	private String value;
	private String inputId;

	public String getType() {
		return this.type;
	}

	public SubjectLocation setType(final String type) {
		this.type = type;
		return this;
	}

	public String getValue() {
		return this.value;
	}

	public SubjectLocation setValue(final String value) {
		this.value = value;
		return this;
	}

	public String getInputId() {
		return this.inputId;
	}

	public SubjectLocation setInputId(final String inputId) {
		this.inputId = inputId;
		return this;
	}
}
