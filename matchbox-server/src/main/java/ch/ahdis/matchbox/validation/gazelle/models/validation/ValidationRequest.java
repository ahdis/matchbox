package ch.ahdis.matchbox.validation.gazelle.models.validation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

import java.util.ArrayList;
import java.util.List;

/**
 * The model of a validation request, containing a list of items to validate.
 * <p>
 * Copy-pasted from
 * https://gitlab.inria.fr/gazelle/public/core/validation-service-api/-/blob/2.0.0/validation-v2-api/src/main/java/net/ihe/gazelle/validation/v2/api/business/request/ValidationRequest.java
 *
 * @author Achraf Achkari
 * @author Quentin Ligier
 **/
@JsonRootName(value = "validationRequest")
public class ValidationRequest {

	@JsonProperty(value = "validationProfileId")
	private String validationProfileId;

	@JsonProperty(value = "inputs")
	private List<Input> inputs;

	public String getValidationProfileId() {
		return validationProfileId;
	}

	public ValidationRequest setValidationProfileId(String validationProfileId) {
		this.validationProfileId = validationProfileId;
		return this;
	}

	public List<Input> getInputs() {
		return inputs;
	}

	public ValidationRequest setInputs(List<Input> inputs) {
		this.inputs = inputs;
		return this;
	}
	public ValidationRequest addInput(Input inputs){
		if(this.inputs == null) {
			this.inputs = new ArrayList<>();
		}
		this.inputs.add(inputs);
		return this;
	}

	@JsonIgnore
	public boolean isValidationProfileIdValid(){
		return validationProfileId != null && !validationProfileId.isBlank();
	}

	@JsonIgnore
	public boolean isInputsValid(){
		return inputs != null && !inputs.isEmpty();
	}

	@JsonIgnore
	public boolean isValid(){
		return isInputsValid() && isValidationProfileIdValid();
	}
}
