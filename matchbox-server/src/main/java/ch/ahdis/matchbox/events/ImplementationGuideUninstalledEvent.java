package ch.ahdis.matchbox.events;

import org.springframework.context.ApplicationEvent;

/**
 * Published once an ImplementationGuide has been uninstalled.
 **/
public class ImplementationGuideUninstalledEvent extends ApplicationEvent {

	private final String packageId;
	private final String packageVersion;

	public ImplementationGuideUninstalledEvent(final Object source,
															 final String packageId,
															 final String packageVersion) {
		super(source);
		this.packageId = packageId;
		this.packageVersion = packageVersion;
	}

	public String getPackageId() {
		return this.packageId;
	}

	public String getPackageVersion() {
		return this.packageVersion;
	}

	@Override
	public String toString() {
		return "ImplementationGuideUninstalledEvent{" +
			"packageId='" + packageId + '\'' +
			", packageVersion='" + packageVersion + '\'' +
			'}';
	}
}
