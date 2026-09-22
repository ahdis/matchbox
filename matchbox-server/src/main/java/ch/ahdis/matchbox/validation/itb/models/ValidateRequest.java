package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Request used to validate one or more input contents.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ValidateRequest {
	/**
	 * The relevant test session's identifier.
	 */
	private String sessionId;

	/**
	 * The configuration items to consider for the validation.
	 */
	private List<Configuration> config;

	/**
	 * The inputs to consider for the validation.
	 */
	private List<AnyContent> input;

	public String getSessionId() {
		return this.sessionId;
	}

	public ValidateRequest setSessionId(final String sessionId) {
		this.sessionId = sessionId;
		return this;
	}

	public List<Configuration> getConfig() {
		return this.config;
	}

	public ValidateRequest setConfig(final List<Configuration> config) {
		this.config = config;
		return this;
	}

	public ValidateRequest addConfig(final Configuration config) {
		if (this.config == null) {
			this.config = new ArrayList<>();
		}
		this.config.add(config);
		return this;
	}

	public List<AnyContent> getInput() {
		return this.input;
	}

	public ValidateRequest setInput(final List<AnyContent> input) {
		this.input = input;
		return this;
	}

	public ValidateRequest addInput(final AnyContent input) {
		if (this.input == null) {
			this.input = new ArrayList<>();
		}
		this.input.add(input);
		return this;
	}
}
