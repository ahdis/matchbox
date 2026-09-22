package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Descriptive metadata for a module definition.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Metadata {
	/**
	 * The name of the service.
	 */
	private String name;

	/**
	 * The version of the service.
	 */
	private String version;

	/**
	 * The authors of the service.
	 */
	private String authors;

	/**
	 * A description for the service.
	 */
	private String description;

	/**
	 * The date when the service was published.
	 */
	private String published;

	/**
	 * The date when the service was last modified.
	 */
	private String lastModified;

	public String getName() {
		return this.name;
	}

	public Metadata setName(final String name) {
		this.name = name;
		return this;
	}

	public String getVersion() {
		return this.version;
	}

	public Metadata setVersion(final String version) {
		this.version = version;
		return this;
	}

	public String getAuthors() {
		return this.authors;
	}

	public Metadata setAuthors(final String authors) {
		this.authors = authors;
		return this;
	}

	public String getDescription() {
		return this.description;
	}

	public Metadata setDescription(final String description) {
		this.description = description;
		return this;
	}

	public String getPublished() {
		return this.published;
	}

	public Metadata setPublished(final String published) {
		this.published = published;
		return this;
	}

	public String getLastModified() {
		return this.lastModified;
	}

	public Metadata setLastModified(final String lastModified) {
		this.lastModified = lastModified;
		return this;
	}
}
