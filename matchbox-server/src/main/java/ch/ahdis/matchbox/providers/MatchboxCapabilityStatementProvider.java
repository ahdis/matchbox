package ch.ahdis.matchbox.providers;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.dao.data.MbInstalledStructureDefinitionRepository;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ServerCapabilityStatementProvider;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.util.TerserUtil;
import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.config.MatchboxFhirVersion;
import ch.ahdis.matchbox.config.property.MatchboxFhirProperties;
import ch.ahdis.matchbox.engine.cli.VersionUtil;
import ch.ahdis.matchbox.engine.exception.MatchboxUnsupportedFhirVersionException;
import ch.ahdis.matchbox.questionnaire.QuestionnaireResponseExtractProvider;
import ch.ahdis.matchbox.validation.ValidationProvider;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.*;
import org.hl7.fhir.r5.model.OperationDefinition.OperationDefinitionParameterComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;

/**
 * A provider of CapabilityStatement customized for Matchbox.
 */
public class MatchboxCapabilityStatementProvider extends ServerCapabilityStatementProvider {
	private static final Logger log = LoggerFactory.getLogger(MatchboxCapabilityStatementProvider.class);
	private static final String VALIDATE_OPERATION_NAME = "Validate";
	private static final String EXT_VALIDATION_DEFAULT_VALUE = "http://matchbox.health/validationDefaultValue";

	private final StructureDefinitionResourceProvider structureDefinitionProvider;
	private final CliContext cliContext;
	private final FhirContext myFhirContext;
	private final MatchboxFhirVersion matchboxFhirVersion;
	private final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository;
	private final MatchboxFhirProperties matchboxFhirProperties;

	public MatchboxCapabilityStatementProvider(final FhirContext fhirContext,
	                                           final RestfulServer theServerConfiguration,
	                                           final StructureDefinitionResourceProvider structureDefinitionProvider,
	                                           final CliContext cliContext,
	                                           final MatchboxFhirVersion matchboxFhirVersion,
	                                           final MbInstalledStructureDefinitionRepository installedStructureDefinitionRepository,
	                                           final MatchboxFhirProperties matchboxFhirProperties) {
		super(theServerConfiguration, null, null);
		this.structureDefinitionProvider = structureDefinitionProvider;
		this.cliContext = cliContext;
		theServerConfiguration.setServerName(VersionUtil.getPoweredBy());
		theServerConfiguration.setServerVersion(VersionUtil.getVersion());
		if (matchboxFhirProperties.getContext().isOnlyOneEngine()) {
			theServerConfiguration.setImplementationDescription("Development mode");
		}
		this.myFhirContext = fhirContext;
		this.matchboxFhirVersion = matchboxFhirVersion;
		this.installedStructureDefinitionRepository = installedStructureDefinitionRepository;
		this.matchboxFhirProperties = matchboxFhirProperties;
	}

	protected void postProcessRestResource(FhirTerser theTerser, IBase theResource, String theResourceName) {
	}

	private static void setField(
		FhirContext theFhirContext,
		FhirTerser theTerser,
		String theFieldName,
		IBase theBase,
		IBase... theValues) {
		BaseRuntimeElementDefinition definition = theFhirContext.getElementDefinition(theBase.getClass());
		BaseRuntimeChildDefinition childDefinition = definition.getChildByName(theFieldName);
		for (IBase value : theValues) {
			try {
				childDefinition.getMutator().addValue(theBase, value);
			} catch (UnsupportedOperationException e) {
				childDefinition.getMutator().setValue(theBase, value);
				break;
			}
		}
		return;
	}

	/**
	 * We need to clean up the default capability statement, in development mode we allow update and create on all
	 * conformance resources otherwise just read access
	 */
	@Override
	protected void postProcess(FhirTerser theTerser, IBaseConformance theCapabilityStatement) {
		final var resources = TerserUtil.getFieldByFhirPath(this.myFhirContext, "rest.resource", theCapabilityStatement);

		for (final IBase resource : resources) {
			final var baseType = TerserUtil.getFirstFieldByFhirPath(this.myFhirContext, "type", resource);
			final String type;
			final IBase interaction;
			final IBase interactionSearch;
			if (baseType instanceof final StringType stringTypeR5) {
				type = stringTypeR5.getValueNotNull();
				interaction = new CapabilityStatement.ResourceInteractionComponent(CapabilityStatement.TypeRestfulInteraction.READ);
				interactionSearch = new CapabilityStatement.ResourceInteractionComponent(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE);
			} else if (baseType instanceof final org.hl7.fhir.r4.model.StringType stringTypeR4) {
				type = stringTypeR4.getValueNotNull();
				interaction =
					new org.hl7.fhir.r4.model.CapabilityStatement.ResourceInteractionComponent(new org.hl7.fhir.r4.model.Enumeration<>(
						new org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteractionEnumFactory(),
						org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction.READ));
				interactionSearch =
					new org.hl7.fhir.r4.model.CapabilityStatement.ResourceInteractionComponent(new org.hl7.fhir.r4.model.Enumeration<>(
						new org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteractionEnumFactory(),
						org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE));
			} else if (baseType instanceof final org.hl7.fhir.r4b.model.StringType stringTypeR4B) {
				type = stringTypeR4B.getValueNotNull();
				interaction =
					new org.hl7.fhir.r4b.model.CapabilityStatement.ResourceInteractionComponent(org.hl7.fhir.r4b.model.CapabilityStatement.TypeRestfulInteraction.READ);
				interactionSearch =
					new org.hl7.fhir.r4b.model.CapabilityStatement.ResourceInteractionComponent(org.hl7.fhir.r4b.model.CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE);
			} else {
				throw new MatchboxUnsupportedFhirVersionException("MatchboxCapabilityStatementProvider",
				                                                  this.myFhirContext.getVersion().getVersion());
			}

			if (!this.matchboxFhirProperties.getContext().isOnlyOneEngine() && ("ImplementationGuide".equals(type))) {
				TerserUtil.clearField(myFhirContext, "interaction", resource);
				setField(myFhirContext, theTerser, "interaction", resource, interaction, interactionSearch);
			}
			if (!this.matchboxFhirProperties.getContext().isOnlyOneEngine() && ("StructureDefinition".equals(type) || "StructureMap".equals(type))) {
				TerserUtil.clearField(myFhirContext, "interaction", resource);
				TerserUtil.clearField(myFhirContext, "searchParam", resource);
				TerserUtil.clearField(myFhirContext, "conditionalCreate", resource);
				TerserUtil.clearField(myFhirContext, "conditionalUpdate", resource);
			}
			TerserUtil.clearField(myFhirContext, "searchRevInclude", resource);
			TerserUtil.clearField(myFhirContext, "searchInclude", resource);
			// IBase value = TerserUtil.newElement(myFhirContext, "boolean", "false");
			// setField(myFhirContext, theTerser, "conditionalCreate", resource, value);
			// setField(myFhirContext, theTerser, "conditionalUpdate", resource, value);
		}
	}

	/**
	 * A hook on the read operation definition method to update $validate with its parameters.
	 */
	@Read(typeName = "OperationDefinition")
	@Override
	public IBaseResource readOperationDefinition(@IdParam final IIdType theId,
	                                             final RequestDetails theRequestDetails) {
		return this.matchboxFhirVersion.applyOnR5(
			super.readOperationDefinition(theId, theRequestDetails),
			this::updateOperationDefinition,
			OperationDefinition.class
		);
	}

	private OperationDefinition updateOperationDefinition(final OperationDefinition opDefR5) {
		switch (opDefR5.getName()) {
			case VALIDATE_OPERATION_NAME -> this.updateValidateOperationDefinition(opDefR5);
			case QuestionnaireResponseExtractProvider.OPERATION_NAME ->
				QuestionnaireResponseExtractProvider.updateOperationDefinition(opDefR5);
			default -> {
				// Do nothing
			}
		}
		return opDefR5;
	}

	/**
	 * Updates an R5 OperationDefinition with the parameters required for the $validate operation, including the
	 * parameters supported and the list of installed profiles.
	 */
	private void updateValidateOperationDefinition(final OperationDefinition validateOperationDefinition) {
		validateOperationDefinition.addParameter()
			.setName("resource")
			.setUse(Enumerations.OperationParameterUse.IN)
			.setMin(0)
			.setMax("1")
			.setType(Enumerations.FHIRTypes.RESOURCE);
		validateOperationDefinition.addParameter()
			.setName("mode")
			.setUse(Enumerations.OperationParameterUse.IN)
			.setMin(0)
			.setMax("1")
			.setType(Enumerations.FHIRTypes.CODE);

		final var profiles = this.installedStructureDefinitionRepository.findAllValidatable().stream()
			.map(entity -> {
				final var canonical = new CanonicalType(entity.getCanonicalUrl());
				canonical.addExtension("ig-id", new StringType(entity.getPackageId()));
				canonical.addExtension("ig-version", new StringType(entity.getPackageVersion()));
				canonical.addExtension("ig-current", new BooleanType(entity.isCurrent()));
				canonical.addExtension("sd-canonical", new StringType(entity.getCanonicalUrl()));
				canonical.addExtension("sd-title", new StringType(entity.getType() + ": " + entity.getTitle()));
				return canonical;
			}).toList();
		validateOperationDefinition.addParameter()
			.setName("profile")
			.setUse(Enumerations.OperationParameterUse.IN)
			.setMin(0)
			.setMax("1")
			.setType(Enumerations.FHIRTypes.CANONICAL)
			.setTargetProfile(profiles);
		validateOperationDefinition.addParameter()
			.setName("reload")
			.setUse(Enumerations.OperationParameterUse.IN)
			.setMin(0)
			.setMax("1")
			.setType(Enumerations.FHIRTypes.BOOLEAN);

		final var cliContextProperties = this.cliContext.getValidateEngineParameters();
		for (final Field field : cliContextProperties) {
			final var isBoolean = field.getType().equals(boolean.class) || field.getType().equals(Boolean.class);
			field.setAccessible(true);
			try {
				final ValidationParameterBuilder builder = isBoolean ?
					ValidationParameterBuilder.bool(field.getName()) :
					ValidationParameterBuilder.string(field.getName());
				if (field.getType().isArray()) {
					builder.array();
				}

				if (field.getType().isArray()) {
					String[] values = (String[]) field.get(this.cliContext);
					if (values != null && values.length > 0) {
						builder.defaultValues(List.of(values));
					}
				} else {
					builder.defaultValue(field.get(this.cliContext));
				}
				validateOperationDefinition.addParameter(builder.build());
			} catch (final Exception e) {
				log.error("Unable to inspect field", e);
			}
		}

		// Manually add other parameters that can be overridden per request
		final var context = this.matchboxFhirProperties.getContext();
		validateOperationDefinition.addParameter(
			ValidationParameterBuilder.string(ValidationProvider.PARAM_LLM_PROVIDER)
				.defaultValue(context.getLlm().getProvider()).build()
		);
		validateOperationDefinition.addParameter(
			ValidationParameterBuilder.string(ValidationProvider.PARAM_LLM_MODEL_NAME)
				.defaultValue(context.getLlm().getModelName()).build()
		);
		validateOperationDefinition.addParameter(
			ValidationParameterBuilder.string(ValidationProvider.PARAM_LLM_API_KEY).build()
		);

		final var validationProps = this.matchboxFhirProperties.getValidation();
		validateOperationDefinition.addParameter(
			ValidationParameterBuilder.bool(ValidationProvider.PARAM_ANALYZE_ERRORS_WITH_LLM)
				.defaultValue(validationProps.isAnalyzeErrorsWithLlm()).build()
		);
	}

	private static class ValidationParameterBuilder {

		private final String name;
		private final ParameterType type;
		private boolean isArray = false;
		private List<?> defaultValues = null;

		public ValidationParameterBuilder(final String name,
													 final ParameterType type) {
			this.name = name;
			this.type = type;
		}

		public static ValidationParameterBuilder string(final String name) {
			return new ValidationParameterBuilder(name, ParameterType.STRING);
		}

		public static ValidationParameterBuilder bool(final String name) {
			return new ValidationParameterBuilder(name, ParameterType.BOOLEAN);
		}

		public ValidationParameterBuilder array() {
			this.isArray = true;
			return this;
		}

		public ValidationParameterBuilder defaultValue(final Object defaultValue) {
			if (defaultValue == null) {
				return this;
			}
			this.defaultValues = List.of(defaultValue);
			return this;
		}

		public ValidationParameterBuilder defaultValues(final List<?> defaultValues) {
			this.defaultValues = defaultValues;
			return this;
		}

		public OperationDefinitionParameterComponent build() {
			final var component = new OperationDefinitionParameterComponent()
				.setName(this.name)
				.setUse(Enumerations.OperationParameterUse.IN)
				.setMin(0)
				.setMax(this.isArray ? "*" : "1")
				.setType(this.type == ParameterType.STRING ? Enumerations.FHIRTypes.STRING : Enumerations.FHIRTypes.BOOLEAN);
			if (this.defaultValues != null) {
				for (final Object value : this.defaultValues) {
					switch (value) {
						case String stringValue when stringValue.isBlank() -> {}
						case String stringValue when this.type == ParameterType.STRING ->
							component.addExtension(EXT_VALIDATION_DEFAULT_VALUE, new StringType(stringValue));
						case Boolean booleanValue when this.type == ParameterType.BOOLEAN ->
							component.addExtension(EXT_VALIDATION_DEFAULT_VALUE, new BooleanType(booleanValue));
						default -> {
							// Don't add nulls or unsupported types
						}
					}
				}
			}
			return component;
		}

		enum ParameterType {
			STRING, BOOLEAN
		}
	}
}
