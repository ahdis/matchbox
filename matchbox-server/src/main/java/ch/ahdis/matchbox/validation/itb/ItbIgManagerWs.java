package ch.ahdis.matchbox.validation.itb;

import ch.ahdis.matchbox.config.property.MatchboxFhirProperties;
import ch.ahdis.matchbox.events.ImplementationGuideInstalledEvent;
import ch.ahdis.matchbox.packages.MatchboxImplementationGuideProvider;
import com.gitb.model.core.TypedParameter;
import com.gitb.model.ps.ProcessResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ITB (GITB REST) processing service {@code IGManager}: makes sure that an implementation guide is installed.
 * <p>
 * A package that is installed is confirmed. One that is not is installed from the package registry with its
 * dependencies, unless the server is read-only ({@code matchbox.fhir.context.httpReadOnly}). A package that is not
 * and cannot be installed gives a report with the result {@code FAILURE}, which fails the test step.
 * <p>
 * See https://github.com/ahdis/matchbox/issues/681.
 */
@RestController
@RequestMapping(path = "/itb/igManager")
public class ItbIgManagerWs extends ItbProcessingWs {
	private static final Logger log = LoggerFactory.getLogger(ItbIgManagerWs.class);

	static final String INPUT_IG = "ig";
	static final String OUTPUT_LOADED = "loaded";

	// The id and the version go into the URL of the package registry
	private static final Pattern PACKAGE = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]*)#([A-Za-z0-9][A-Za-z0-9._+-]*)");

	private final MatchboxImplementationGuideProvider igProvider;
	private final MatchboxFhirProperties matchboxProps;
	private final ApplicationEventPublisher eventPublisher;

	public ItbIgManagerWs(final MatchboxImplementationGuideProvider igProvider,
								 final MatchboxFhirProperties matchboxProps,
								 final ApplicationEventPublisher eventPublisher) {
		super("IGManager", "matchbox IG manager",
				"Makes sure that an implementation guide is installed in matchbox.", "loadIG");
		this.igProvider = Objects.requireNonNull(igProvider);
		this.matchboxProps = Objects.requireNonNull(matchboxProps);
		this.eventPublisher = Objects.requireNonNull(eventPublisher);
	}

	@Override
	protected List<TypedParameter> getInputs() {
		return List.of(parameter(INPUT_IG, true, "The package, 'id#version' (e.g. hl7.fhir.uv.ips#2.0.0)."));
	}

	@Override
	protected List<TypedParameter> getOutputs() {
		return List.of(parameter(OUTPUT_LOADED, true, "The package that is installed, 'id#version'."));
	}

	@Override
	protected ProcessResponse doProcess(final ItbInputs inputs) {
		final String ig = inputs.require(INPUT_IG).strip();
		final Matcher matcher = PACKAGE.matcher(ig);
		if (!matcher.matches()) {
			throw new ItbBadRequestException(
				"Invalid value '%s' for input '%s', expected 'id#version'".formatted(ig, INPUT_IG));
		}
		final String id = matcher.group(1);
		final String version = matcher.group(2);

		if (!this.igProvider.has(id, version)) {
			if (this.matchboxProps.getContext().isHttpReadOnly()) {
				return failure("The package %s is not installed and the server is read-only, add it to the configuration of matchbox"
										.formatted(ig));
			}
			final String error = this.install(id, version);
			if (error != null) {
				return failure("The package %s could not be installed: %s".formatted(ig, error));
			}
		}
		return ProcessResponse.builder()
			.withOutput(ItbTarMapper.contextItem(OUTPUT_LOADED, ig, "text/plain", true))
			.build();
	}

	/**
	 * Installs the package from the package registry, one at a time.
	 *
	 * @return the error, or {@code null} if the package is installed.
	 */
	private synchronized String install(final String id, final String version) {
		if (this.igProvider.has(id, version)) {
			return null; // installed by another request in the meantime
		}
		log.info("ITB loadIG: installing {}#{}", id, version);
		try {
			this.igProvider.installFromInternetRegistry(id, version);
		} catch (final Exception e) {
			log.error("ITB loadIG: error installing {}#{}", id, version, e);
			return e.getMessage();
		}
		if (!this.igProvider.has(id, version)) {
			return "not found";
		}
		this.eventPublisher.publishEvent(new ImplementationGuideInstalledEvent(this));
		return null;
	}

	private static ProcessResponse failure(final String description) {
		return ProcessResponse.builder().withReport(ItbTarMapper.failure(description)).build();
	}
}
