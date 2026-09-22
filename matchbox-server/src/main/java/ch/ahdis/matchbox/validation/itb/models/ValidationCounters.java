package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Counters summarising assertion, error and warning totals.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ValidationCounters {
	/**
	 * The number of information messages in the report.
	 */
	private Integer nrOfAssertions;

	/**
	 * The number of errors in the report.
	 */
	private Integer nrOfErrors;

	/**
	 * The number of warnings in the report.
	 */
	private Integer nrOfWarnings;

	public Integer getNrOfAssertions() {
		return this.nrOfAssertions;
	}

	public ValidationCounters setNrOfAssertions(final Integer nrOfAssertions) {
		this.nrOfAssertions = nrOfAssertions;
		return this;
	}

	public Integer getNrOfErrors() {
		return this.nrOfErrors;
	}

	public ValidationCounters setNrOfErrors(final Integer nrOfErrors) {
		this.nrOfErrors = nrOfErrors;
		return this;
	}

	public Integer getNrOfWarnings() {
		return this.nrOfWarnings;
	}

	public ValidationCounters setNrOfWarnings(final Integer nrOfWarnings) {
		this.nrOfWarnings = nrOfWarnings;
		return this;
	}
}
