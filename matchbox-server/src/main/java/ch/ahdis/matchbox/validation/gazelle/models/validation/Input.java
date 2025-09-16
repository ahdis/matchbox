package ch.ahdis.matchbox.validation.gazelle.models.validation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;

import java.net.URI;

/**
 * The model of a validation item.
 * <p>
 * Copy-pasted from
 * https://gitlab.inria.fr/gazelle/public/core/validation-service-api/-/blob/2.0.0/validation-v2-api/src/main/java/net/ihe/gazelle/validation/v2/api/business/Input.java?ref_type=tags
 *
 * @author Achraf Achkari
 * @author Quentin Ligier
 **/
@JsonRootName(value = "validationItem")
public class Input {

	@JsonProperty(value = "id")
	private String id;

	@JsonProperty(value = "itemId")
	private String itemId;

	@JsonProperty(value = "content")
	private byte[] content;

	@JsonProperty(value = "location")
	private String location;

	public String getId() {
		return this.id;
	}

	public Input setId(final String id) {
		this.id = id;
		return this;
	}

	public String getItemId() {
		return itemId;
	}

	public Input setItemId(String itemId) {
		this.itemId = itemId;
		return this;
	}

	public byte[] getContent() {
		return content;
	}

	public Input setContent(byte[] content) {
		this.content = content;
		return this;
	}

	public String getLocation() {
		return location;
	}

	public Input setLocation(String location) {
		this.location = location;
		return this;
	}

	@JsonIgnore
	public boolean isContentValid() {
		return content != null && content.length > 0;
	}

	@JsonIgnore
	public boolean isItemIdValid() {
		return itemId == null || !itemId.isBlank();
	}

	@JsonIgnore
	public boolean isLocationValid(){
		return location == null || isURLValid(location);
	}

	@JsonIgnore
	public boolean isURLValid(String url) {
		try {
			new URI(url).toURL();
		} catch (Exception e) {
			return false;
		}
		return true;
	}

	@JsonIgnore
	public boolean isValid(){
		return isContentValid() && isItemIdValid() && isLocationValid();
	}

	public static Input clone(Input input) {
		return new Input()
			.setId(input.getId())
			.setItemId(input.getItemId())
			.setContent(input.getContent() != null ? input.getContent().clone():null)
			.setLocation(input.getLocation());
	}
}
