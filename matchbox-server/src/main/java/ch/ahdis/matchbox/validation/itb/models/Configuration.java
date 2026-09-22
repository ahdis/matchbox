package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Named configuration value.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Configuration {
	private String value;

	private String name;

	public String getValue() {
		return this.value;
	}

	public Configuration setValue(final String value) {
		this.value = value;
		return this;
	}

	public String getName() {
		return this.name;
	}

	public Configuration setName(final String name) {
		this.name = name;
		return this;
	}
}
