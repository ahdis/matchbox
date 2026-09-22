package ch.ahdis.matchbox.validation.itb.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response containing the advertised validation module definition.
 * <p>
 * From the GITB validation service REST API ({@code gitb_vs.json}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GetModuleDefinitionResponse {
	private ValidationModule module;

	public ValidationModule getModule() {
		return this.module;
	}

	public GetModuleDefinitionResponse setModule(final ValidationModule module) {
		this.module = module;
		return this;
	}
}
