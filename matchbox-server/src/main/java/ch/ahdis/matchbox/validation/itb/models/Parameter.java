package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Definition of a configuration parameter.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Parameter {
	/**
	 * The parameter's value.
	 */
	private String value;

	/**
	 * The parameter's name value.
	 */
	private String name;

	/**
	 * The parameter's label.
	 */
	private String label;

	private UsageEnumeration use;

	private ConfigurationType kind;

	/**
	 * The parameter's description.
	 */
	private String desc;

	public String getValue() {
		return this.value;
	}

	public Parameter setValue(final String value) {
		this.value = value;
		return this;
	}

	public String getName() {
		return this.name;
	}

	public Parameter setName(final String name) {
		this.name = name;
		return this;
	}

	public String getLabel() {
		return this.label;
	}

	public Parameter setLabel(final String label) {
		this.label = label;
		return this;
	}

	public UsageEnumeration getUse() {
		return this.use;
	}

	public Parameter setUse(final UsageEnumeration use) {
		this.use = use;
		return this;
	}

	public ConfigurationType getKind() {
		return this.kind;
	}

	public Parameter setKind(final ConfigurationType kind) {
		this.kind = kind;
		return this;
	}

	public String getDesc() {
		return this.desc;
	}

	public Parameter setDesc(final String desc) {
		this.desc = desc;
		return this;
	}
}
