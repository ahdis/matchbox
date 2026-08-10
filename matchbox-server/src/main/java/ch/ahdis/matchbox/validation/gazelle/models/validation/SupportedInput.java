package ch.ahdis.matchbox.validation.gazelle.models.validation;

public class SupportedInput {

	private String id;
	private String label;
	private boolean required = true;

	/**
	 * Default constructor.
	 */
	public SupportedInput() {
		// Default constructor
	}

	/**
	 * Gets the id of the input.
	 *
	 * @return the id
	 */
	public String getId() {
		return id;
	}

	/**
	 * Sets the id of the input.
	 *
	 * @param id the id
	 *
	 * @return the current SupportedInput instance
	 */
	public SupportedInput setId(String id) {
		this.id = id;
		return this;
	}

	/**
	 * Gets the human-readable label of the input.
	 *
	 * @return the label
	 */
	public String getLabel() {
		return label;
	}

	/**
	 * Sets the human-readable label of the input.
	 *
	 * @param label the label
	 *
	 * @return the current SupportedInput instance
	 */
	public SupportedInput setLabel(String label) {
		this.label = label;
		return this;
	}

	/**
	 * Indicates whether the input is required.
	 *
	 * @return true if the input is required, false if optional.
	 */
	public boolean isRequired() {
		return required;
	}

	/**
	 * Sets whether the input is required (true by default).
	 *
	 * @param required true if the input is required, false if optional.
	 *
	 * @return the current SupportedInput instance
	 */
	public SupportedInput setRequired(boolean required) {
		this.required = required;
		return this;
	}

}
