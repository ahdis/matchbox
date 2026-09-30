package ch.ahdis.matchbox.events;

import org.springframework.context.ApplicationEvent;

/**
 * Published after a package version has been added to or removed from the package cache in the database, once the
 * transaction has completed. Unlike {@link ImplementationGuideInstalledEvent}, it's published for every package
 * version, whatever the way it's installed (also dependencies, $install-npm-package, …).
 */
public class InstalledPackagesChangedEvent extends ApplicationEvent {

	public InstalledPackagesChangedEvent(final Object source) {
		super(source);
	}
}
