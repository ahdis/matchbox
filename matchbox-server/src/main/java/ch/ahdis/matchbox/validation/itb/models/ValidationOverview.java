package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * High-level metadata describing a validation execution.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ValidationOverview {
	/**
	 * The relevant specification profile ID.
	 */
	@JsonProperty("profileID")
	private String profileID;

	/**
	 * The relevant specification customization ID.
	 */
	@JsonProperty("customizationID")
	private String customizationID;

	/**
	 * The relevant specification transaction ID.
	 */
	@JsonProperty("transactionID")
	private String transactionID;

	/**
	 * The service name.
	 */
	private String validationServiceName;

	/**
	 * The service version.
	 */
	private String validationServiceVersion;

	/**
	 * A note for the report.
	 */
	private String note;

	@JsonProperty("profileID")
	public String getProfileID() {
		return this.profileID;
	}

	@JsonProperty("profileID")
	public ValidationOverview setProfileID(final String profileID) {
		this.profileID = profileID;
		return this;
	}

	@JsonProperty("customizationID")
	public String getCustomizationID() {
		return this.customizationID;
	}

	@JsonProperty("customizationID")
	public ValidationOverview setCustomizationID(final String customizationID) {
		this.customizationID = customizationID;
		return this;
	}

	@JsonProperty("transactionID")
	public String getTransactionID() {
		return this.transactionID;
	}

	@JsonProperty("transactionID")
	public ValidationOverview setTransactionID(final String transactionID) {
		this.transactionID = transactionID;
		return this;
	}

	public String getValidationServiceName() {
		return this.validationServiceName;
	}

	public ValidationOverview setValidationServiceName(final String validationServiceName) {
		this.validationServiceName = validationServiceName;
		return this;
	}

	public String getValidationServiceVersion() {
		return this.validationServiceVersion;
	}

	public ValidationOverview setValidationServiceVersion(final String validationServiceVersion) {
		this.validationServiceVersion = validationServiceVersion;
		return this;
	}

	public String getNote() {
		return this.note;
	}

	public ValidationOverview setNote(final String note) {
		this.note = note;
		return this;
	}
}
