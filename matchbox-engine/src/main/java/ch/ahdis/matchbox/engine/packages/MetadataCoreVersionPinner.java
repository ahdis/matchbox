package ch.ahdis.matchbox.engine.packages;

import java.util.List;

import org.hl7.fhir.r5.context.BaseWorkerContext;
import org.hl7.fhir.r5.model.CanonicalResource;
import org.hl7.fhir.r5.model.CanonicalType;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.ElementDefinition;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.r5.model.ValueSet;
import org.hl7.fhir.utilities.UserDataNames;

/**
 * Pins the canonical references of the resources of a core package to the versions of the referenced resources, like
 * org.hl7.fhir.r5.context.CoreVersionPinner does when the core package is loaded (SimpleWorkerContext.finishLoading()).
 * <p>
 * CoreVersionPinner fetches the referenced resources, which would parse all the lazily loaded CodeSystems and
 * ValueSets. This pinner reads the versions from the metadata of the registered resources instead
 * ({@link BaseWorkerContext#getResourceVersion}), so the core CodeSystems and ValueSets can be loaded lazily and pinned
 * when they're parsed.
 */
public class MetadataCoreVersionPinner {

	private final BaseWorkerContext context;

	public MetadataCoreVersionPinner(final BaseWorkerContext context) {
		this.context = context;
	}

	public void pinCoreVersions(final List<CodeSystem> cslist,
										 final List<ValueSet> vslist,
										 final List<StructureDefinition> sdList) {
		for (final CodeSystem cs : cslist) {
			this.pin(cs.getValueSetElement(), ValueSet.class, true);
			this.pin(cs.getSupplementsElement(), CodeSystem.class, true);
		}
		for (final ValueSet vs : vslist) {
			for (final ValueSet.ConceptSetComponent vsi : vs.getCompose().getInclude()) {
				this.pinCoreVersions(vsi);
			}
			for (final ValueSet.ConceptSetComponent vsi : vs.getCompose().getExclude()) {
				this.pinCoreVersions(vsi);
			}
		}
		for (final StructureDefinition sd : sdList) {
			this.pin(sd.getBaseDefinitionElement(), StructureDefinition.class, false);
			for (final ElementDefinition ed : sd.getDifferential().getElement()) {
				this.pinCoreVersions(ed);
			}
			for (final ElementDefinition ed : sd.getSnapshot().getElement()) {
				this.pinCoreVersions(ed);
			}
		}
	}

	private void pinCoreVersions(final ElementDefinition ed) {
		for (final ElementDefinition.TypeRefComponent tr : ed.getType()) {
			for (final CanonicalType ct : tr.getProfile()) {
				this.pin(ct, StructureDefinition.class, false);
			}
			for (final CanonicalType ct : tr.getTargetProfile()) {
				this.pin(ct, StructureDefinition.class, false);
			}
		}
		for (final CanonicalType ct : ed.getValueAlternatives()) {
			this.pin(ct, StructureDefinition.class, false);
		}
		if (ed.hasBinding()) {
			this.pin(ed.getBinding().getValueSetElement(), ValueSet.class, true);
			for (final ElementDefinition.ElementDefinitionBindingAdditionalComponent adb : ed.getBinding().getAdditional()) {
				this.pin(adb.getValueSetElement(), ValueSet.class, true);
			}
		}
	}

	private void pinCoreVersions(final ValueSet.ConceptSetComponent vsi) {
		for (final CanonicalType ct : vsi.getValueSet()) {
			this.pin(ct, ValueSet.class, true);
		}
		if (vsi.hasSystem() && !vsi.hasVersion() && !vsi.getSystem().contains("terminology.hl7.org")) {
			final String version = this.context.getResourceVersion(CodeSystem.class, vsi.getSystem());
			if (version != null) {
				vsi.setVersion(version);
				vsi.getVersionElement().setUserData(UserDataNames.VERSION_PINNED_ON_LOAD, true);
			}
		}
	}

	private void pin(final CanonicalType ct,
						  final Class<? extends CanonicalResource> type,
						  final boolean skipTerminologyHl7Org) {
		if (ct == null || !ct.hasValue() || ct.getValue().contains("|")
			|| (skipTerminologyHl7Org && ct.getValue().contains("terminology.hl7.org"))) {
			return;
		}
		final String version = this.context.getResourceVersion(type, ct.getValue());
		if (version != null) {
			ct.setValue(ct.getValue() + "|" + version);
			ct.setUserData(UserDataNames.VERSION_PINNED_ON_LOAD, true);
		}
	}
}
