package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Parameter definition enriched with a value type and optional encoding.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TypedParameter {
	/**
	 * The parameter's value.
	 */
	private String value;

	/**
	 * The parameter's name.
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

	/**
	 * The parameter's type.
	 */
	private String type;

	/**
	 * The parameter's encoding.
	 */
	private String encoding;

	public String getValue() {
		return this.value;
	}

	public TypedParameter setValue(final String value) {
		this.value = value;
		return this;
	}

	public String getName() {
		return this.name;
	}

	public TypedParameter setName(final String name) {
		this.name = name;
		return this;
	}

	public String getLabel() {
		return this.label;
	}

	public TypedParameter setLabel(final String label) {
		this.label = label;
		return this;
	}

	public UsageEnumeration getUse() {
		return this.use;
	}

	public TypedParameter setUse(final UsageEnumeration use) {
		this.use = use;
		return this;
	}

	public ConfigurationType getKind() {
		return this.kind;
	}

	public TypedParameter setKind(final ConfigurationType kind) {
		this.kind = kind;
		return this;
	}

	public String getDesc() {
		return this.desc;
	}

	public TypedParameter setDesc(final String desc) {
		this.desc = desc;
		return this;
	}

	public String getType() {
		return this.type;
	}

	public TypedParameter setType(final String type) {
		this.type = type;
		return this;
	}

	public String getEncoding() {
		return this.encoding;
	}

	public TypedParameter setEncoding(final String encoding) {
		this.encoding = encoding;
		return this;
	}
}
