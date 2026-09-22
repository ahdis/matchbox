package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response containing the validation report.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ValidationResponse {
	private TAR report;

	public TAR getReport() {
		return this.report;
	}

	public ValidationResponse setReport(final TAR report) {
		this.report = report;
		return this;
	}
}
