package ch.ahdis.matchbox.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The properties in 'matchbox.fhir'.
 *
 * @author Quentin Ligier
 **/
@Component
@ConfigurationProperties(prefix = "matchbox.fhir")
public class MatchboxFhirProperties {

	private MatchboxFhirContextProperties context = new MatchboxFhirContextProperties();

	private MatchboxFhirMcpProperties mcp = new MatchboxFhirMcpProperties();

	private MatchboxFhirValidationProperties validation = new MatchboxFhirValidationProperties();

	public MatchboxFhirContextProperties getContext() {
		return this.context;
	}

	public void setContext(final MatchboxFhirContextProperties context) {
		this.context = context;
	}

	public MatchboxFhirMcpProperties getMcp() {
		return this.mcp;
	}

	public void setMcp(final MatchboxFhirMcpProperties mcp) {
		this.mcp = mcp;
	}

	public MatchboxFhirValidationProperties getValidation() {
		return this.validation;
	}

	public void setValidation(final MatchboxFhirValidationProperties validation) {
		this.validation = validation;
	}
}
