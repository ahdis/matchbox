package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursively defined content wrapper used to transport text, binary or referenced values.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnyContent {
	/**
	 * Child content items (when parent is a list or a map).
	 */
	private List<AnyContent> item;

	/**
	 * The item's value, represented as a string.
	 */
	private String value;

	/**
	 * The item's name.
	 */
	private String name;

	private ValueEmbeddingEnumeration embeddingMethod;

	/**
	 * The type of content (default is string).
	 */
	private String type;

	/**
	 * The text encoding of the content.
	 */
	private String encoding;

	/**
	 * The content's content type (or mime type).
	 */
	private String mimeType;

	/**
	 * Whether the value should be included in the test session context (default is true).
	 */
	private Boolean forContext;

	/**
	 * Whether the value should be included in the displayed report for the step in question (default is true).
	 */
	private Boolean forDisplay;

	public List<AnyContent> getItem() {
		return this.item;
	}

	public AnyContent setItem(final List<AnyContent> item) {
		this.item = item;
		return this;
	}

	public AnyContent addItem(final AnyContent item) {
		if (this.item == null) {
			this.item = new ArrayList<>();
		}
		this.item.add(item);
		return this;
	}

	public String getValue() {
		return this.value;
	}

	public AnyContent setValue(final String value) {
		this.value = value;
		return this;
	}

	public String getName() {
		return this.name;
	}

	public AnyContent setName(final String name) {
		this.name = name;
		return this;
	}

	public ValueEmbeddingEnumeration getEmbeddingMethod() {
		return this.embeddingMethod;
	}

	public AnyContent setEmbeddingMethod(final ValueEmbeddingEnumeration embeddingMethod) {
		this.embeddingMethod = embeddingMethod;
		return this;
	}

	public String getType() {
		return this.type;
	}

	public AnyContent setType(final String type) {
		this.type = type;
		return this;
	}

	public String getEncoding() {
		return this.encoding;
	}

	public AnyContent setEncoding(final String encoding) {
		this.encoding = encoding;
		return this;
	}

	public String getMimeType() {
		return this.mimeType;
	}

	public AnyContent setMimeType(final String mimeType) {
		this.mimeType = mimeType;
		return this;
	}

	public Boolean getForContext() {
		return this.forContext;
	}

	public AnyContent setForContext(final Boolean forContext) {
		this.forContext = forContext;
		return this;
	}

	public Boolean getForDisplay() {
		return this.forDisplay;
	}

	public AnyContent setForDisplay(final Boolean forDisplay) {
		this.forDisplay = forDisplay;
		return this;
	}
}
