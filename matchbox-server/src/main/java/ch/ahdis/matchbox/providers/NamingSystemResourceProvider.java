package ch.ahdis.matchbox.providers;

import org.hl7.fhir.r4.model.NamingSystem;

public class NamingSystemResourceProvider extends ConformancePackageResourceProvider<NamingSystem, org.hl7.fhir.r4b.model.NamingSystem, org.hl7.fhir.r5.model.NamingSystem> {

	public NamingSystemResourceProvider() {
		super(NamingSystem.class, org.hl7.fhir.r4b.model.NamingSystem.class, org.hl7.fhir.r5.model.NamingSystem.class);
	}

}
