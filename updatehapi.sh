# org.hl7.fhir.core 7: validation and transformation are based on the versionless R6 modules (org.hl7.fhir.model,
# org.hl7.fhir.services, org.hl7.fhir.standalone), the patches to the r5 classes are applied to their R6 counterparts
cp ../org.hl7.fhir.core/org.hl7.fhir.utilities/src/main/java/org/hl7/fhir/utilities/npm/FilesystemPackageCacheManager.java matchbox-engine/src/main/java/org/hl7/fhir/utilities/npm/
cp ../org.hl7.fhir.core/org.hl7.fhir.services/src/main/java/org/hl7/fhir/services/elementmodel/XmlParser.java matchbox-engine/src/main/java/org/hl7/fhir/services/elementmodel/
cp ../org.hl7.fhir.core/org.hl7.fhir.services/src/main/java/org/hl7/fhir/services/elementmodel/Element.java matchbox-engine/src/main/java/org/hl7/fhir/services/elementmodel/
cp ../org.hl7.fhir.core/org.hl7.fhir.services/src/main/java/org/hl7/fhir/services/fml/FHIRPathHostServices.java matchbox-engine/src/main/java/org/hl7/fhir/services/fml/
cp ../org.hl7.fhir.core/org.hl7.fhir.services/src/main/java/org/hl7/fhir/services/fml/StructureMapTools.java matchbox-engine/src/main/java/org/hl7/fhir/services/fml/
cp ../org.hl7.fhir.core/org.hl7.fhir.services/src/main/java/org/hl7/fhir/services/conformance/profile/ProfileUtilities.java matchbox-engine/src/main/java/org/hl7/fhir/services/conformance/profile/
cp ../org.hl7.fhir.core/org.hl7.fhir.services/src/main/java/org/hl7/fhir/services/testfactory/TestDataFactory.java matchbox-engine/src/main/java/org/hl7/fhir/services/testfactory/
cp ../org.hl7.fhir.core/org.hl7.fhir.services/src/main/java/org/hl7/fhir/services/context/CanonicalResourceProxy.java matchbox-engine/src/main/java/org/hl7/fhir/services/context/
cp ../org.hl7.fhir.core/org.hl7.fhir.standalone/src/main/java/org/hl7/fhir/standalone/context/BaseWorkerContext.java matchbox-engine/src/main/java/org/hl7/fhir/standalone/context/
cp ../org.hl7.fhir.core/org.hl7.fhir.standalone/src/main/java/org/hl7/fhir/standalone/context/CanonicalResourceManager.java matchbox-engine/src/main/java/org/hl7/fhir/standalone/context/
cp ../org.hl7.fhir.core/org.hl7.fhir.validation/src/main/java/org/hl7/fhir/validation/instance/InstanceValidator.java matchbox-engine/src/main/java/org/hl7/fhir/validation/instance/
cp ../org.hl7.fhir.core/org.hl7.fhir.validation/src/main/java/org/hl7/fhir/validation/instance/type/BundleValidator.java matchbox-engine/src/main/java/org/hl7/fhir/validation/instance/type
cp ../org.hl7.fhir.core/org.hl7.fhir.validation/src/main/java/org/hl7/fhir/validation/BaseValidator.java matchbox-engine/src/main/java/org/hl7/fhir/validation/
# matchbox patch #614 (skipNarrative)
cp ../org.hl7.fhir.core/org.hl7.fhir.convertors/src/main/java/org/hl7/fhir/convertors/loaders/loaderRN/BaseLoaderRN.java matchbox-engine/src/main/java/org/hl7/fhir/convertors/loaders/loaderRN/
cp ../org.hl7.fhir.core/org.hl7.fhir.convertors/src/main/java/org/hl7/fhir/convertors/loaders/loaderRN/R3ToRNLoader.java matchbox-engine/src/main/java/org/hl7/fhir/convertors/loaders/loaderRN/
cp ../org.hl7.fhir.core/org.hl7.fhir.convertors/src/main/java/org/hl7/fhir/convertors/loaders/loaderRN/R4ToRNLoader.java matchbox-engine/src/main/java/org/hl7/fhir/convertors/loaders/loaderRN/
cp ../org.hl7.fhir.core/org.hl7.fhir.convertors/src/main/java/org/hl7/fhir/convertors/loaders/loaderRN/R4BToRNLoader.java matchbox-engine/src/main/java/org/hl7/fhir/convertors/loaders/loaderRN/
cp ../org.hl7.fhir.core/org.hl7.fhir.convertors/src/main/java/org/hl7/fhir/convertors/loaders/loaderRN/R5ToRNLoader.java matchbox-engine/src/main/java/org/hl7/fhir/convertors/loaders/loaderRN/
# cp ../org.hl7.fhir.core/org.hl7.fhir.validation.cli/src/main/java/org/hl7/fhir/validation/cli/param/Params.java matchbox-engine/src/main/java/org/hl7/fhir/validation/cli/param
# HAPI FHIR is built with org.hl7.fhir.core 6: ILoggingService moved to org.hl7.fhir.utilities.logging in core 7,
# remove once HAPI FHIR is built with org.hl7.fhir.core 7
cp ../hapi-fhir/hapi-fhir-validation/src/main/java/org/hl7/fhir/common/hapi/validation/validator/WorkerContextValidationSupportAdapter.java matchbox-server/src/main/java/org/hl7/fhir/common/hapi/validation/validator/
cp ../hapi-fhir/hapi-fhir-structures-r5/src/main/java/org/hl7/fhir/r5/hapi/ctx/HapiWorkerContext.java matchbox-server/src/main/java/org/hl7/fhir/r5/hapi/ctx/
cp ../hapi-fhir/hapi-fhir-structures-r4b/src/main/java/org/hl7/fhir/r4b/hapi/ctx/HapiWorkerContext.java matchbox-server/src/main/java/org/hl7/fhir/r4b/hapi/ctx/
cp ../hapi-fhir/hapi-fhir-structures-r4/src/main/java/org/hl7/fhir/r4/hapi/ctx/HapiWorkerContext.java matchbox-server/src/main/java/org/hl7/fhir/r4/hapi/ctx/
cp ../hapi-fhir/hapi-fhir-structures-dstu3/src/main/java/org/hl7/fhir/dstu3/hapi/ctx/HapiWorkerContext.java matchbox-server/src/main/java/org/hl7/fhir/dstu3/hapi/ctx/
# cp ../hapi-fhir/hapi-fhir-server/src/main/java/ca/uhn/fhir/rest/server/method/ResourceParameter.java matchbox-server/src/main/java/ca/uhn/fhir/rest/server/method
# cp ../hapi-fhir/hapi-fhir-server/src/main/java/ca/uhn/fhir/rest/server/RestfulServerUtils.java matchbox-server/src/main/java/ca/uhn/fhir/rest/server
# cp ../hapi-fhir/hapi-fhir-jpaserver-base/src/main/java/ca/uhn/fhir/jpa/packages/loader/PackageLoaderSvc.java matchbox-server/src/main/java/ca/uhn/fhir/jpa/packages/loader/
# cp ../hapi-fhir/hapi-fhir-jpaserver-base/src/main/java/ca/uhn/fhir/jpa/packages/JpaPackageCache.java matchbox-server/src/main/java/ca/uhn/fhir/jpa/packages/
