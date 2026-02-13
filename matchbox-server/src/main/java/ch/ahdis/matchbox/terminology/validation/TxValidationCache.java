package ch.ahdis.matchbox.terminology.validation;

import org.apache.commons.collections4.map.PassiveExpiringMap;
import org.hl7.fhir.r5.model.ValueSet;

import jakarta.annotation.Nullable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class TxValidationCache {

	/**
	 * Maximum number of ValueSet expansions per cache ID to prevent memory leaks
	 */
	private static final int MAX_VALUESETS_PER_CACHE_ID = 100;

	/**
	 * A cache that stores a mapping from value set URLs to expanded value sets, per cache ID.
	 */
	private final Map<String, Map<String, ValueSet>> valueSetCache =
		new PassiveExpiringMap<>(5, TimeUnit.MINUTES);

	@Nullable
	public ValueSet getValueSet(final String cacheId,
										 final String url) {
		return Optional.ofNullable(valueSetCache.get(cacheId))
				.map(map -> map.get(url))
				.orElse(null);
	}

	public void cacheValueSet(final String cacheId,
									  final String url,
									  final ValueSet valueSet) {
		this.valueSetCache
			.computeIfAbsent(cacheId, k -> createBoundedLinkedHashMap())
			.put(url, valueSet);
	}

	/**
	 * Creates a bounded LRU cache for ValueSets to prevent memory leaks
	 */
	private Map<String, ValueSet> createBoundedLinkedHashMap() {
		return new LinkedHashMap<String, ValueSet>(20, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, ValueSet> eldest) {
				return size() > MAX_VALUESETS_PER_CACHE_ID;
			}
		};
	}
}
