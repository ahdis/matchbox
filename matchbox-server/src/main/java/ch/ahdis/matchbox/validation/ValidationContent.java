package ch.ahdis.matchbox.validation;

import ca.uhn.fhir.rest.api.EncodingEnum;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import org.hl7.fhir.utilities.xml.XMLUtil;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * The content to validate in a type-level {@code [base]/{Type}/$validate} request, with the profiles it declares.
 * <p>
 * The body is either the resource of type {@code {Type}} itself, or a {@code Parameters} envelope with a
 * {@code resource} parameter of type {@code {Type}} (and optionally a {@code profile} parameter). The type in the URL
 * tells them apart, so a {@code Parameters} posted to {@code Parameters/$validate} is never read as an envelope.
 * <p>
 * The body is only inspected, not parsed with HAPI: the validator has to see the content as it was sent.
 *
 * @param content         the resource to validate, in the encoding of the request.
 * @param envelopeProfile the {@code profile} parameter of the envelope, if any.
 * @param metaProfiles    the {@code meta.profile} values of the resource to validate.
 */
public record ValidationContent(String content,
                                @Nullable String envelopeProfile,
                                List<String> metaProfiles) {

	private static final String PARAMETERS = "Parameters";
	private static final String PARAM_RESOURCE = "resource";
	private static final String PARAM_PROFILE = "profile";
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final JsonFactory JSON_FACTORY = new JsonFactory();

	/**
	 * Resolves the content to validate from the body of a type-level $validate request.
	 * <p>
	 * A body that can't be read (malformed JSON or XML, no resource type) is returned as is: the validator reports
	 * what's wrong with it.
	 *
	 * @param body     the HTTP body.
	 * @param encoding the encoding of the body.
	 * @param type     the resource type of the request URL.
	 * @throws ValidationContentException if the body is neither a {@code type} nor an envelope of a {@code type}.
	 */
	public static ValidationContent resolve(final String body,
	                                        final EncodingEnum encoding,
	                                        final String type) throws ValidationContentException {
		return encoding == EncodingEnum.XML ? resolveXml(body, type) : resolveJson(body, type);
	}

	private static ValidationContent resolveJson(final String body, final String type) throws ValidationContentException {
		final JsonNode root;
		try {
			root = OBJECT_MAPPER.readTree(withoutBom(body));
		} catch (final IOException e) {
			return asIs(body);
		}
		final String rootType = root == null ? null : root.path("resourceType").asText(null);
		if (rootType == null) {
			return asIs(body);
		}
		if (rootType.equals(type)) {
			return new ValidationContent(body, null, metaProfilesJson(root));
		}
		if (!PARAMETERS.equals(rootType)) {
			throw unexpectedType(type, rootType);
		}

		String envelopeProfile = null;
		int resourceIndex = -1;
		JsonNode resource = null;
		int index = 0;
		for (final JsonNode parameter : root.path("parameter")) {
			final String name = parameter.path("name").asText("");
			if (PARAM_RESOURCE.equals(name) && resource == null && parameter.path("resource").isObject()) {
				resource = parameter.path("resource");
				resourceIndex = index;
			} else if (PARAM_PROFILE.equals(name) && envelopeProfile == null) {
				for (final var field : parameter.properties()) {
					if (field.getKey().startsWith("value") && field.getValue().isTextual()) {
						envelopeProfile = field.getValue().asText();
					}
				}
			}
			++index;
		}
		if (resource == null) {
			throw missingResource(type);
		}
		final String resourceType = resource.path("resourceType").asText(null);
		if (!type.equals(resourceType)) {
			throw unexpectedEnvelopedType(type, resourceType);
		}
		final String content;
		try {
			content = extractJsonResource(withoutBom(body), resourceIndex);
		} catch (final IOException e) {
			return asIs(body);
		}
		if (content == null) {
			return asIs(body);
		}
		return new ValidationContent(content, envelopeProfile, metaProfilesJson(resource));
	}

	/**
	 * Cuts {@code parameter[index].resource} out of the body, without parsing and encoding it again: numbers, the
	 * order of the properties and duplicate properties stay as they were sent.
	 */
	private static @Nullable String extractJsonResource(final String body, final int index) throws IOException {
		try (final JsonParser parser = JSON_FACTORY.createParser(body)) {
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				return null;
			}
			while (parser.nextToken() == JsonToken.FIELD_NAME) {
				final boolean isParameter = "parameter".equals(parser.currentName());
				if (parser.nextToken() != JsonToken.START_ARRAY || !isParameter) {
					parser.skipChildren();
					continue;
				}
				for (int i = 0; parser.nextToken() == JsonToken.START_OBJECT; ++i) {
					if (i != index) {
						parser.skipChildren();
						continue;
					}
					while (parser.nextToken() == JsonToken.FIELD_NAME) {
						final boolean isResource = PARAM_RESOURCE.equals(parser.currentName());
						if (parser.nextToken() == JsonToken.START_OBJECT && isResource) {
							final int start = (int) parser.currentTokenLocation().getCharOffset();
							parser.skipChildren();
							return body.substring(start, (int) parser.currentLocation().getCharOffset());
						}
						parser.skipChildren();
					}
					return null;
				}
			}
		}
		return null;
	}

	private static List<String> metaProfilesJson(final JsonNode resource) {
		final List<String> profiles = new ArrayList<>();
		for (final JsonNode profile : resource.path("meta").path("profile")) {
			if (profile.isTextual() && !profile.asText().isBlank()) {
				profiles.add(profile.asText());
			}
		}
		return profiles;
	}

	private static ValidationContent resolveXml(final String body, final String type) throws ValidationContentException {
		final Element root;
		try {
			final var factory = XMLUtil.newXXEProtectedDocumentBuilderFactory();
			// Already set by the core factory, stated here so that it doesn't depend on it: no DOCTYPE, no entities
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setNamespaceAware(true);
			final var builder = factory.newDocumentBuilder();
			builder.setErrorHandler(new DefaultHandler()); // don't print the parsing errors to stderr
			root = builder.parse(new InputSource(new StringReader(withoutBom(body)))).getDocumentElement();
		} catch (final Exception e) {
			return asIs(body);
		}
		final String rootType = root.getLocalName();
		if (rootType.equals(type)) {
			return new ValidationContent(body, null, metaProfilesXml(root));
		}
		if (!PARAMETERS.equals(rootType)) {
			throw unexpectedType(type, rootType);
		}

		String envelopeProfile = null;
		Element resource = null;
		for (final Element parameter : children(root, "parameter")) {
			final String name = children(parameter, "name").stream()
				.map(element -> element.getAttribute("value"))
				.findFirst()
				.orElse("");
			if (PARAM_RESOURCE.equals(name) && resource == null) {
				resource = children(parameter, "resource").stream()
					.flatMap(element -> children(element, null).stream())
					.findFirst()
					.orElse(null);
			} else if (PARAM_PROFILE.equals(name) && envelopeProfile == null) {
				envelopeProfile = children(parameter, null).stream()
					.filter(element -> element.getLocalName().startsWith("value") && element.hasAttribute("value"))
					.map(element -> element.getAttribute("value"))
					.findFirst()
					.orElse(null);
			}
		}
		if (resource == null) {
			throw missingResource(type);
		}
		if (!type.equals(resource.getLocalName())) {
			throw unexpectedEnvelopedType(type, resource.getLocalName());
		}
		try {
			// The resource inherits its namespaces from the envelope, it has to be serialized as its own document
			final var transformer = XMLUtil.newXXEProtectedTransformerFactory().newTransformer();
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
			final var writer = new StringWriter();
			transformer.transform(new DOMSource(resource), new StreamResult(writer));
			return new ValidationContent(writer.toString(), envelopeProfile, metaProfilesXml(resource));
		} catch (final Exception e) {
			throw new ValidationContentException(
				"The '%s' in the 'resource' parameter could not be extracted: %s".formatted(type, e.getMessage()));
		}
	}

	private static List<String> metaProfilesXml(final Element resource) {
		return children(resource, "meta").stream()
			.flatMap(meta -> children(meta, "profile").stream())
			.map(profile -> profile.getAttribute("value"))
			.filter(profile -> !profile.isBlank())
			.toList();
	}

	/**
	 * Returns the child elements with the given local name, or all child elements if the name is {@code null}.
	 */
	private static List<Element> children(final Element parent, final @Nullable String localName) {
		final List<Element> children = new ArrayList<>();
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof final Element element && (localName == null || localName.equals(element.getLocalName()))) {
				children.add(element);
			}
		}
		return children;
	}

	private static ValidationContent asIs(final String body) {
		return new ValidationContent(body, null, List.of());
	}

	private static String withoutBom(final String body) {
		return body.startsWith("﻿") ? body.substring(1) : body;
	}

	private static ValidationContentException unexpectedType(final String type, final String found) {
		return new ValidationContentException(
			"Expected a '%s', or a 'Parameters' with a 'resource' parameter of type '%s', but found a '%s'".formatted(
				type, type, found));
	}

	private static ValidationContentException unexpectedEnvelopedType(final String type, final @Nullable String found) {
		return new ValidationContentException(
			"Expected the 'resource' parameter to be of type '%s', but found type '%s'".formatted(type, found));
	}

	private static ValidationContentException missingResource(final String type) {
		return new ValidationContentException(
			"Expected a '%s', or a 'Parameters' with a 'resource' parameter of type '%s', but the 'Parameters' has no 'resource' parameter".formatted(
				type, type));
	}

	/**
	 * The body of a type-level $validate request is not what the type in the URL asks for.
	 */
	public static class ValidationContentException extends Exception {
		public ValidationContentException(final String message) {
			super(message);
		}
	}
}
