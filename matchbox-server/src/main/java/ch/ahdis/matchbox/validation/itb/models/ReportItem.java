package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Report item entry included in a validation, messaging or processing step report.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportItem {
	/**
	 * The item's assertion ID.
	 */
	@JsonProperty("assertionID")
	private String assertionID;

	/**
	 * The item's description.
	 */
	private String description;

	/**
	 * The item's relevant location in provided content.
	 */
	private String location;

	/**
	 * The item's test expression.
	 */
	private String test;

	/**
	 * The item's type.
	 */
	private String type;

	/**
	 * The item's considered value for the relevant assertion.
	 */
	private String value;

	private SeverityLevel level;

	@JsonProperty("assertionID")
	public String getAssertionID() {
		return this.assertionID;
	}

	@JsonProperty("assertionID")
	public ReportItem setAssertionID(final String assertionID) {
		this.assertionID = assertionID;
		return this;
	}

	public String getDescription() {
		return this.description;
	}

	public ReportItem setDescription(final String description) {
		this.description = description;
		return this;
	}

	public String getLocation() {
		return this.location;
	}

	public ReportItem setLocation(final String location) {
		this.location = location;
		return this;
	}

	public String getTest() {
		return this.test;
	}

	public ReportItem setTest(final String test) {
		this.test = test;
		return this;
	}

	public String getType() {
		return this.type;
	}

	public ReportItem setType(final String type) {
		this.type = type;
		return this;
	}

	public String getValue() {
		return this.value;
	}

	public ReportItem setValue(final String value) {
		this.value = value;
		return this;
	}

	public SeverityLevel getLevel() {
		return this.level;
	}

	public ReportItem setLevel(final SeverityLevel level) {
		this.level = level;
		return this;
	}
}
