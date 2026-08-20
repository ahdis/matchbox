package ch.ahdis.matchbox.config.property;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The properties in 'matchbox.fhir.validation'.
 *
 * @author Quentin Ligier
 **/
@Component
@ConfigurationProperties(prefix = "matchbox.fhir.validation")
public class MatchboxFhirValidationProperties {

	private boolean analyzeErrorsWithLlm = false;

	public boolean isAnalyzeErrorsWithLlm() {
		return this.analyzeErrorsWithLlm;
	}

	public void setAnalyzeErrorsWithLlm(final boolean analyzeErrorsWithLlm) {
		this.analyzeErrorsWithLlm = analyzeErrorsWithLlm;
	}
}
