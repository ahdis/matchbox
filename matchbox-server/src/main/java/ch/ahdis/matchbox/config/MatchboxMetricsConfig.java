package ch.ahdis.matchbox.config;

import ch.ahdis.matchbox.packages.MatchboxImplementationGuideProvider;
import ch.ahdis.matchbox.util.MatchboxEngineSupport;
import ch.ahdis.matchbox.util.metrics.MatchboxMetrics;
import dev.langchain4j.observation.listener.ObservationChatModelListener;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for collecting Matchbox metrics and exposing them via Micrometer.
 **/
@Configuration
@ConditionalOnProperty(prefix = "otel.instrumentation.micrometer", name = "enabled", havingValue = "true")
public class MatchboxMetricsConfig {
	private static final String ENGINE_UNIT = "engines";

	@Bean
	public MeterBinder exposeNumberOfCachedEngines(final MatchboxEngineSupport engineSupport) {
		final var engineCache = engineSupport.getSessionCache();
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

	/**
	 * Registers the langchain4j metrics listener.
	 * See <a href="https://docs.langchain4j.dev/tutorials/spring-boot-integration/#micrometer-metrics">Spring Boot Integration</a>
	 */
	@Bean
	public ObservationChatModelListener langchain4jListener(final ObservationRegistry observationRegistry,
																			  final MeterRegistry meterRegistry) {
		return new ObservationChatModelListener(observationRegistry, meterRegistry);
	}
}
