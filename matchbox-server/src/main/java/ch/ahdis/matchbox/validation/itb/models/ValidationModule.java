package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Definition of a validation service module.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ValidationModule {
	private Metadata metadata;

	/**
	 * The inputs accepted by this service.
	 */
	private List<TypedParameter> inputs;

	/**
	 * The outputs produced by this service.
	 */
	private List<TypedParameter> outputs;

	/**
	 * The service's identifier.
	 */
	private String id;

	/**
	 * The service's URI.
	 */
	private String uri;

	/**
	 * The service's location address.
	 */
	private String serviceLocation;

	/**
	 * The service's supported configuration values.
	 */
	private List<Parameter> configs;

	/**
	 * The service's operation.
	 */
	private String operation;

	public Metadata getMetadata() {
		return this.metadata;
	}

	public ValidationModule setMetadata(final Metadata metadata) {
		this.metadata = metadata;
		return this;
	}

	public List<TypedParameter> getInputs() {
		return this.inputs;
	}

	public ValidationModule setInputs(final List<TypedParameter> inputs) {
		this.inputs = inputs;
		return this;
	}

	public ValidationModule addInput(final TypedParameter input) {
		if (this.inputs == null) {
			this.inputs = new ArrayList<>();
		}
		this.inputs.add(input);
		return this;
	}

	public List<TypedParameter> getOutputs() {
		return this.outputs;
	}

	public ValidationModule setOutputs(final List<TypedParameter> outputs) {
		this.outputs = outputs;
		return this;
	}

	public ValidationModule addOutput(final TypedParameter output) {
		if (this.outputs == null) {
			this.outputs = new ArrayList<>();
		}
		this.outputs.add(output);
		return this;
	}

	public String getId() {
		return this.id;
	}

	public ValidationModule setId(final String id) {
		this.id = id;
		return this;
	}

	public String getUri() {
		return this.uri;
	}

	public ValidationModule setUri(final String uri) {
		this.uri = uri;
		return this;
	}

	public String getServiceLocation() {
		return this.serviceLocation;
	}

	public ValidationModule setServiceLocation(final String serviceLocation) {
		this.serviceLocation = serviceLocation;
		return this;
	}

	public List<Parameter> getConfigs() {
		return this.configs;
	}

	public ValidationModule setConfigs(final List<Parameter> configs) {
		this.configs = configs;
		return this;
	}

	public ValidationModule addConfig(final Parameter config) {
		if (this.configs == null) {
			this.configs = new ArrayList<>();
		}
		this.configs.add(config);
		return this;
	}

	public String getOperation() {
		return this.operation;
	}

	public ValidationModule setOperation(final String operation) {
		this.operation = operation;
		return this;
	}
}
