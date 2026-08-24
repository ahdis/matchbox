/*
 * #%L
 * Matchbox
 * %%
 * Copyright (c) 2023- by ahdis ag. All rights reserved.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package ch.ahdis.matchbox.util;

import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.engine.MatchboxEngine;
import jakarta.annotation.Nullable;
import org.apache.commons.collections4.map.PassiveExpiringMap;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * @author Oliver Egger
 * <p>
 * We want to have a validation engines also that are not timed out as in the parent class.
 */
public class MatchboxEngineCache {

	private final Map<String, MatchboxEngine> permanentEngines = new ConcurrentHashMap<>(8);
	private final Map<String, MatchboxEngine> transientEngines =
		Collections.synchronizedMap(new PassiveExpiringMap<>(60, TimeUnit.MINUTES));

	public String cacheTransientEngine(final CliContext cliContext,
												  final MatchboxEngine engine) {
		final String sessionId = cliContext.sessionId();
		this.transientEngines.put(sessionId, engine);
		return sessionId;
	}

	public String cachePermanentEngine(final CliContext cliContext,
												  final MatchboxEngine engine) {
		final String sessionId = cliContext.sessionId();
		this.permanentEngines.put(sessionId, engine);
		return sessionId;
	}

	@Nullable
	public MatchboxEngine fetchEngine(final String sessionId) {
		MatchboxEngine engine = this.permanentEngines.get(sessionId);
		if (engine != null) {
			return engine;
		}

		engine = this.transientEngines.get(sessionId);
		if (engine != null) {
			// Reset the expiration time of the engine
			this.transientEngines.put(sessionId, engine);
			return engine;
		}
		return null;
	}

	@Nullable
	public MatchboxEngine fetchEngine(final CliContext cliContext) {
		return this.fetchEngine(cliContext.sessionId());
	}

	public Set<String> getSessionIds() {
		final HashSet<String> sessionIds =
			HashSet.newHashSet(this.transientEngines.size() + this.permanentEngines.size());
		sessionIds.addAll(this.permanentEngines.keySet());
		synchronized (this.transientEngines) {
			sessionIds.addAll(this.transientEngines.keySet());
		}
		return sessionIds;
	}

	@Nullable
	public String findSessionId(final MatchboxEngine engine) {
		for (final Map.Entry<String, MatchboxEngine> entry : this.permanentEngines.entrySet()) {
			if (entry.getValue() == engine) {
				return entry.getKey();
			}
		}

		synchronized (this.transientEngines) {
			for (final Map.Entry<String, MatchboxEngine> entry : this.transientEngines.entrySet()) {
				if (entry.getValue() == engine) {
					return entry.getKey();
				}
			}
		}
		return null;
	}

	public int numberOfTransientEngines() {
		return this.transientEngines.size();
	}

	public int numberOfPermanentEngines() {
		return this.permanentEngines.size();
	}

	/**
	 * Removes from the cache (both permanent and transient engines) any engine that has the given package loaded in
	 * its context. This is used when an ImplementationGuide is uninstalled, so that stale engines still holding it in
	 * memory are evicted and get recreated (without it) the next time they are requested.
	 *
	 * @param packageId      the id of the uninstalled package, e.g. "hl7.fhir.us.core"
	 * @param packageVersion the version of the uninstalled package, e.g. "6.1.0"
	 * @return the number of engines that were evicted from the cache.
	 */
	public int evictEnginesWithPackage(final String packageId, final String packageVersion) {
		final String pkg = packageId + "#" + packageVersion;
		int removed = removeEnginesWithPackage(this.permanentEngines, pkg);
		synchronized (this.transientEngines) {
			removed += removeEnginesWithPackage(this.transientEngines, pkg);
		}
		return removed;
	}

	private static int removeEnginesWithPackage(final Map<String, MatchboxEngine> engines, final String pkg) {
		int removed = 0;
		final Iterator<Map.Entry<String, MatchboxEngine>> it = engines.entrySet().iterator();
		while (it.hasNext()) {
			final Map.Entry<String, MatchboxEngine> entry = it.next();
			if (entry.getValue().getContext().getLoadedPackages().contains(pkg)) {
				it.remove();
				++removed;
			}
		}
		return removed;
	}
}
