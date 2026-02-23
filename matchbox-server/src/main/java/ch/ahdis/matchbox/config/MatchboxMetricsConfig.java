package ch.ahdis.matchbox.config;

import ch.ahdis.matchbox.packages.MatchboxImplementationGuideProvider;
import ch.ahdis.matchbox.util.MatchboxEngineCache;
import ch.ahdis.matchbox.util.metrics.MatchboxMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for collecting Matchbox metrics and exposing them via Micrometer.
 **/
@Configuration
public class MatchboxMetricsConfig {
	private static final String ENGINE_UNIT = "engines";

	@Bean
	public MeterBinder exposeNumberOfCachedEngines(final MatchboxEngineCache engineCache) {
		return registry -> {
			Gauge.builder("matchbox.engines.cached.transient.number", engineCache::numberOfTransientEngines)
				.description("Number of cached expiring Matchbox engines in the server")
				.baseUnit(ENGINE_UNIT)
				.register(registry);
			Gauge.builder("matchbox.engines.cached.permanent.number", engineCache::numberOfPermanentEngines)
				.description("Number of cached immutable Matchbox engines in the server")
				.baseUnit(ENGINE_UNIT)
				.register(registry);
		};
	}

	@Bean
	public MeterBinder exposeNumberOfIgs(final MatchboxImplementationGuideProvider implementationGuideProvider) {
		return registry -> Gauge.builder("matchbox.igs.number", implementationGuideProvider::count)
			.description("Number of installed ImplementationGuides")
			.baseUnit("ImplementationGuides")
			.register(registry);
	}

	@Bean
	public MatchboxMetrics matchboxMetrics(final MeterRegistry meterRegistry) {
		return new MatchboxMetrics(meterRegistry);
	}
}
