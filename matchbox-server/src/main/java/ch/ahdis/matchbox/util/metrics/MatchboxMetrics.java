package ch.ahdis.matchbox.util.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * The class holding different metrics that Matchbox may collect.
 *
 * @see ch.ahdis.matchbox.config.MatchboxMetricsConfig
 **/
public class MatchboxMetrics {

	private final Counter validationCounter;
	private final Timer validationDurationTimer;
	private final Counter transformationCounter;

	public MatchboxMetrics(final MeterRegistry meterRegistry) {
		this.validationCounter = Counter.builder("matchbox.validation.count")
			.description("Number of FHIR resources validated by Matchbox")
			.baseUnit("validations")
			.register(meterRegistry);
		this.validationDurationTimer = Timer.builder("matchbox.validation.duration")
			.description("Duration of FHIR resource validation by Matchbox")
			.distributionStatisticExpiry(Duration.ofSeconds(30))
			.distributionStatisticBufferLength(10)
			.register(meterRegistry);
		this.transformationCounter = Counter.builder("matchbox.transformation.count")
			.description("Number of FHIR resources transformed by Matchbox")
			.baseUnit("transformations")
			.register(meterRegistry);
	}

	public void addValidation() {
		this.validationCounter.increment();
	}

	public void addValidationDuration(final Duration duration) {
		this.validationDurationTimer.record(duration);
	}

	public void addTransformation() {
		this.transformationCounter.increment();
	}
}
