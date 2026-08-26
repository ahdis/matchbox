package ch.ahdis.matchbox.events;

import org.springframework.context.ApplicationEvent;

/**
 * Published once an installation request has fully completed: the requested ImplementationGuide(s) together with
 * every dependency they pulled in (there can be several, transitively) have all been installed.
 **/
public class ImplementationGuideInstalledEvent extends ApplicationEvent {

	public ImplementationGuideInstalledEvent(final Object source) {
		super(source);
	}
}
