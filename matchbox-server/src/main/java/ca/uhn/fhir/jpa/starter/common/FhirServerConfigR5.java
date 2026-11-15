package ca.uhn.fhir.jpa.starter.common;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.config.JpaConfig;
import ca.uhn.fhir.jpa.config.r5.JpaR5Config;
import ca.uhn.fhir.jpa.dao.JpaResourceDao;
import ca.uhn.fhir.jpa.starter.annotations.OnMatchboxOnlyOneEnginePresent;
import ca.uhn.fhir.jpa.starter.annotations.OnR5Condition;
import ca.uhn.fhir.jpa.starter.annotations.OnStatisticsEnabled;
import ca.uhn.fhir.jpa.validation.ValidatorPolicyAdvisor;
import ca.uhn.fhir.jpa.validation.ValidatorResourceFetcher;
import ca.uhn.fhir.validation.IInstanceValidatorModule;
import ch.ahdis.matchbox.config.MatchboxJpaConfig;
import ch.ahdis.matchbox.packages.ImplementationGuideProviderR5;
import ch.ahdis.matchbox.questionnaire.QuestionnaireAssembleProviderR5;
import ch.ahdis.matchbox.questionnaire.QuestionnaireResponseExtractProviderR5;
import ch.ahdis.matchbox.statistics.OperationOutcomeResourceProviderR5;
import ch.ahdis.matchbox.statistics.SearchParameterResourceProviderR5;
import ch.ahdis.matchbox.util.LazyDefaultProfileValidationSupport;
import ch.ahdis.matchbox.util.MatchboxEngineSupport;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.common.hapi.validation.validator.WorkerContextValidationSupportAdapter;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.SearchParameter;
import org.hl7.fhir.r5.model.ImplementationGuide;
import org.hl7.fhir.r5.model.StructureMap;
import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;
import org.springframework.context.annotation.*;

@Configuration
@Conditional(OnR5Condition.class)
@Import({
  MatchboxJpaConfig.class,
  JpaR5Config.class
})
public class FhirServerConfigR5 {

  private final FhirContext fhirContext;

  public FhirServerConfigR5(final FhirContext fhirContext) {
    this.fhirContext = fhirContext;
    // The JPA search parameter registry reads the built-in SearchParameters from the validation support of the cached
    // FhirContext (ReadOnlySearchParamCache.fromFhirContext()), whose default would load the complete R5 core package
    final FhirContext cachedFhirContext = FhirContext.forR5Cached();
    cachedFhirContext.setValidationSupport(new LazyDefaultProfileValidationSupport(cachedFhirContext));
  }

  /**
   * Replaces the bean of ValidationSupportConfig (imported through JpaR5Config), which loads the R5 core package into
   * memory at startup.
   */
  @Bean(name = JpaConfig.DEFAULT_PROFILE_VALIDATION_SUPPORT)
  public IValidationSupport defaultProfileValidationSupport() {
    return new LazyDefaultProfileValidationSupport(fhirContext);
  }

  /**
   * Replaces the bean of ValidationSupportConfig, which creates the FhirInstanceValidator with the FhirContext: that
   * makes the FhirContext create its default validation support, which loads the R5 core package into memory, before
   * it's replaced by the validation support chain.
   */
  @Bean(name = "myInstanceValidator")
  public IInstanceValidatorModule instanceValidator(final IValidationSupport theValidationSupportChain,
                                                    final WorkerContextValidationSupportAdapter theWrappedWorkerContext,
                                                    final ValidatorResourceFetcher theValidatorResourceFetcher,
                                                    final ValidatorPolicyAdvisor theValidatorPolicyAdvisor) {
    final var val = new FhirInstanceValidator(theValidationSupportChain);
    val.setWrappedWorkerContext(theValidationSupportChain, theWrappedWorkerContext);
    val.setValidatorResourceFetcher(theValidatorResourceFetcher);
    val.setValidatorPolicyAdvisor(theValidatorPolicyAdvisor);
    val.setBestPracticeWarningLevel(BestPracticeWarningLevel.Warning);
    return val;
  }

  @Bean
  public QuestionnaireAssembleProviderR5 assembleProvider() {
    return new QuestionnaireAssembleProviderR5();
  }

  @Bean
  public QuestionnaireResponseExtractProviderR5 questionnaireResponseProvider(final MatchboxEngineSupport matchboxEngineSupport) {
    return new QuestionnaireResponseExtractProviderR5(matchboxEngineSupport);
  }

  @Bean(name = "myImplementationGuideDaoR5")
  public IFhirResourceDao<ImplementationGuide> daoImplementationGuideR5() {
    final var retVal = new JpaResourceDao<ImplementationGuide>();
    retVal.setResourceType(ImplementationGuide.class);
    retVal.setContext(fhirContext);
    return retVal;
  }

  @Bean
  @Primary
  public ImplementationGuideProviderR5 rpImplementationGuideR5() {
    final var retVal = new ImplementationGuideProviderR5();
    retVal.setContext(fhirContext);
//    retVal.setDao(daoImplementationGuideR5());
    return retVal;
  }

  @Bean(name = "myStructureMapDaoR5")
  @Conditional(OnMatchboxOnlyOneEnginePresent.class)
  public IFhirResourceDao<StructureMap> daoStructureMapR5() {
    final var retVal = new JpaResourceDao<StructureMap>();
    retVal.setResourceType(StructureMap.class);
    retVal.setContext(fhirContext);
    return retVal;
  }
  
  @Bean
  @Primary
  @Conditional(OnStatisticsEnabled.class)
  public OperationOutcomeResourceProviderR5 rpOperationOutcomeR5(final IFhirResourceDao<OperationOutcome> operationOutcomeDao,
                                                                 final FhirContext fhirContext) {
    final var retVal = new OperationOutcomeResourceProviderR5();
    retVal.setContext(fhirContext);
    retVal.setDao(operationOutcomeDao);
    return retVal;
  }

  @Bean
  @Primary
  @Conditional(OnStatisticsEnabled.class)
  public SearchParameterResourceProviderR5 rpSearchParameterR5(final IFhirResourceDao<SearchParameter> searchParameterDao,
                                                               final FhirContext fhirContext) {
    final var retVal = new SearchParameterResourceProviderR5();
    retVal.setContext(fhirContext);                                                            
    retVal.setDao(searchParameterDao);
    return retVal;
  }
}
