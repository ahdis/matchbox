package ch.ahdis.matchbox.terminology.validation;

import org.apache.commons.collections4.map.PassiveExpiringMap;
import org.hl7.fhir.r5.model.ValueSet;

import jakarta.annotation.Nullable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Cache for terminology validation ValueSet expansions.
 * 
 * MEMORY LEAK PREVENTION:
 * Inner maps use LinkedHashMap with LRU eviction policy to prevent unbounded growth.
 * See createBoundedLinkedHashMap() and docs/memory-leak-prevention.md for details.
 */
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
	 * Creates a bounded LRU cache for ValueSets to prevent memory leaks.
	 * 
	 * IMPORTANT: Simply using LinkedHashMap does NOT prevent memory leaks.
	 * What prevents the leak is the combination of:
	 * 1. LinkedHashMap with accessOrder=true (3rd parameter) - maintains access order
	 * 2. removeEldestEntry() override - automatically removes oldest entry when size exceeds limit
	 * 
	 * When a new ValueSet is added and size() > MAX_VALUESETS_PER_CACHE_ID:
	 * - removeEldestEntry() returns true
	 * - LinkedHashMap automatically removes the least recently used entry
	 * - This implements an LRU (Least Recently Used) cache with bounded size
	 * 
	 * See docs/memory-leak-prevention.md for detailed explanation.
	 * 
	 * @return A bounded LinkedHashMap with LRU eviction policy
	 */
	private Map<String, ValueSet> createBoundedLinkedHashMap() {
		return new LinkedHashMap<String, ValueSet>(20, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, ValueSet> eldest) {
				// When this returns true, the eldest (least recently used) entry is automatically removed
				return size() > MAX_VALUESETS_PER_CACHE_ID;
			}
		};
	}
}
