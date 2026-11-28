package ch.ahdis.matchbox.validation.itb;

import ch.ahdis.matchbox.packages.OnDemandIgInstaller;
import com.gitb.model.core.TypedParameter;
import com.gitb.model.ps.ProcessResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * The ITB (GITB REST) processing service {@code IGManager}: makes sure that an implementation guide is installed.
 * <p>
 * A package that is installed is confirmed. One that is not is installed by the {@link OnDemandIgInstaller}, unless
 * the server is read-only. A package that is not and cannot be installed gives a report with the result
 * {@code FAILURE}, which fails the test step.
 * <p>
 * See https://github.com/ahdis/matchbox/issues/681.
 */
@RestController
@RequestMapping(path = "/itb/igManager")
public class ItbIgManagerWs extends ItbProcessingWs {
	static final String INPUT_IG = "ig";
	static final String OUTPUT_LOADED = "loaded";

	private final OnDemandIgInstaller igInstaller;

	public ItbIgManagerWs(final OnDemandIgInstaller igInstaller) {
		super("IGManager", "matchbox IG manager",
				"Makes sure that an implementation guide is installed in matchbox.", "loadIG");
		this.igInstaller = Objects.requireNonNull(igInstaller);
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
		if (!OnDemandIgInstaller.isPackageWithVersion(ig)) {
			throw new ItbBadRequestException(
				"Invalid value '%s' for input '%s', expected 'id#version'".formatted(ig, INPUT_IG));
		}
		try {
			this.igInstaller.ensureInstalled(ig);
		} catch (final OnDemandIgInstaller.IgInstallationException e) {
			return failure(e.getMessage());
		}
		return ProcessResponse.builder()
			.withOutput(ItbTarMapper.contextItem(OUTPUT_LOADED, ig, "text/plain", true))
			.build();
	}

	private static ProcessResponse failure(final String description) {
		return ProcessResponse.builder().withReport(ItbTarMapper.failure(description)).build();
	}
}
