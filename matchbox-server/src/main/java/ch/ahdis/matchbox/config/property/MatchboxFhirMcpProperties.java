package ch.ahdis.matchbox.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The properties in 'matchbox.fhir.mcp'.
 *
 * @author Quentin Ligier
 **/
@Component
@ConfigurationProperties(prefix = "matchbox.fhir.mcp")
public class MatchboxFhirMcpProperties {

	private boolean requestAnalysisFromClient = false;

	public boolean isRequestAnalysisFromClient() {
		return this.requestAnalysisFromClient;
	}

	public void setRequestAnalysisFromClient(final boolean requestAnalysisFromClient) {
		this.requestAnalysisFromClient = requestAnalysisFromClient;
	}
}
