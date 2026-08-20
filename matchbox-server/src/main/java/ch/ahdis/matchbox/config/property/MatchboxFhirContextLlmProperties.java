package ch.ahdis.matchbox.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The properties in 'matchbox.fhir.context.llm'.
 *
 * @author Quentin Ligier
 **/
@Component
@ConfigurationProperties(prefix = "matchbox.fhir.context")
public class MatchboxFhirContextLlmProperties {

	private String provider;

	private String modelName;

	private String apiKey;

	public String getProvider() {
		return this.provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public String getModelName() {
		return this.modelName;
	}

	public void setModelName(String modelName) {
		this.modelName = modelName;
	}

	public String getApiKey() {
		return this.apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public boolean isValid() {
		return this.provider != null && !this.provider.isEmpty()
			&& this.modelName != null && !this.modelName.isEmpty()
			&& this.apiKey != null && !this.apiKey.isEmpty();
	}

	public MatchboxFhirContextLlmProperties clone() {
		final var clone = new MatchboxFhirContextLlmProperties();
		clone.setProvider(this.provider);
		clone.setModelName(this.modelName);
		clone.setApiKey(this.apiKey);
		return clone;
	}
}
