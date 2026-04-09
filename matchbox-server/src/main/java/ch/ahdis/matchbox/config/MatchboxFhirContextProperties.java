package ch.ahdis.matchbox.config;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * The properties in 'matchbox.fhir.context'.
 *
 * @author Quentin Ligier
 **/
@Component
@ConfigurationProperties(prefix = "matchbox.fhir.context")
public class MatchboxFhirContextProperties {

	private @Nullable Map<String, Set<String>> suppressWarnInfo, suppressError;

	public @Nullable Map<String, Set<String>> getSuppressWarnInfo() {
		return this.suppressWarnInfo;
	}

	public void setSuppressWarnInfo(final @Nullable Map<String, Set<String>> suppressWarnInfo) {
		this.suppressWarnInfo = suppressWarnInfo;
	}

	public @Nullable Map<String, Set<String>> getSuppressError() {
		return this.suppressError;
	}

	public void setSuppressError(final @Nullable Map<String, Set<String>> suppressError) {
		this.suppressError = suppressError;
	}
}
