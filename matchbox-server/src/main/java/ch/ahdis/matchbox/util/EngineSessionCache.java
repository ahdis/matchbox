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

import java.util.Collections;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.collections4.map.PassiveExpiringMap;
import org.hl7.fhir.validation.ValidationEngine;
import org.hl7.fhir.validation.service.PassiveExpiringSessionCache;

/**
 * @author Oliver Egger
 * 
 * We want to have validation engines also that are not timed out as
 * in the parent class.
 * 
 * MEMORY LEAK PREVENTION:
 * To prevent memory leaks, the "forever" caches use an LRU (Least Recently Used) eviction
 * policy with a maximum size limit. This is implemented using LinkedHashMap with the
 * removeEldestEntry() method override.
 * 
 * IMPORTANT: Simply changing HashMap to LinkedHashMap does NOT prevent memory leaks.
 * The key is the removeEldestEntry() override that returns true when the size limit is
 * exceeded, causing LinkedHashMap to automatically remove the least recently used entry.
 * 
 * When the limit (MAX_PERMANENT_CACHE_SIZE) is reached:
 * - New entries can still be added
 * - The least recently used entry is automatically removed
 * - This keeps the cache size bounded, preventing OutOfMemoryError
 * 
 * The bidirectional maps (sessionId->engine and engine->sessionId) may temporarily have
 * orphaned entries after eviction, but these will be naturally evicted by the LRU policy,
 * preventing unbounded growth.
 * 
 * See docs/memory-leak-prevention.md for detailed explanation of the pattern.
 */
public class EngineSessionCache extends PassiveExpiringSessionCache {

    // Maximum number of ValidationEngine instances to cache forever to prevent memory leaks
    private static final int MAX_PERMANENT_CACHE_SIZE = 50;
    
    private final Map<String, ValidationEngine> cachedSessionsNoTimeout;
    private final Map<ValidationEngine, String> cachedSessionIdsNoTimeout;
    private final PassiveExpiringMap<ValidationEngine, String> cachedSessionIds;
    
    final static int TEST_TIME_TO_LIVE = 60;
    

    public EngineSessionCache() {
        super(TEST_TIME_TO_LIVE,TIME_UNIT); 
        this.setResetExpirationAfterFetch(true);
        cachedSessionIds = new PassiveExpiringMap<>(TEST_TIME_TO_LIVE, TIME_UNIT);
        
        // IMPORTANT: Create LRU caches with automatic eviction to prevent memory leaks.
        // 
        // Simply changing HashMap to LinkedHashMap does NOT prevent memory leaks.
        // What prevents the leak is the combination of:
        // 1. LinkedHashMap with accessOrder=true (3rd parameter) - maintains access order
        // 2. removeEldestEntry() override - automatically removes oldest entry when size exceeds limit
        // 3. Collections.synchronizedMap() wrapper - provides thread safety
        //
        // When a new entry is added and size() > MAX_PERMANENT_CACHE_SIZE:
        // - removeEldestEntry() returns true
        // - LinkedHashMap automatically removes the least recently used entry
        // - This implements an LRU (Least Recently Used) cache with bounded size
        //
        // See docs/memory-leak-prevention.md for detailed explanation.
        
        this.cachedSessionsNoTimeout = Collections.synchronizedMap(
            new LinkedHashMap<String, ValidationEngine>(MAX_PERMANENT_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ValidationEngine> eldest) {
                    // When this returns true, the eldest (least recently used) entry is automatically removed
                    return size() > MAX_PERMANENT_CACHE_SIZE;
                }
            }
        );
        
        this.cachedSessionIdsNoTimeout = Collections.synchronizedMap(
            new LinkedHashMap<ValidationEngine, String>(MAX_PERMANENT_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ValidationEngine, String> eldest) {
                    // When this returns true, the eldest (least recently used) entry is automatically removed
                    return size() > MAX_PERMANENT_CACHE_SIZE;
                }
            }
        );
    }

    /**
     * Returns the stored {@link ValidationEngine} associated with the passed in
     * session id, if one such instance exists.
     * 
     * @param sessionId The {@link String} session id.
     * @return The {@link ValidationEngine} associated with the passed in id, or
     *         null if none exists.
     */
    @Override
    public ValidationEngine fetchSessionValidatorEngine(String sessionId) {
        cachedSessionIds.keySet(); // https://github.com/ahdis/matchbox/issues/336
        if (cachedSessionsNoTimeout.containsKey(sessionId)) {
            return cachedSessionsNoTimeout.get(sessionId);
        }
        ValidationEngine valEngine = super.fetchSessionValidatorEngine(sessionId);
        if (valEngine!=null && super.resetExpirationAfterFetch) {
            cachedSessionIds.put(valEngine, sessionId);
        }
        return valEngine;
    }

    /**
     * Returns the set of stored session ids.
     * 
     * @return {@link Set} of session ids.
     */
    @Override
    public Set<String> getSessionIds() {
        Set<String> mergedSet = new HashSet<String>();
        mergedSet.addAll(super.getSessionIds());
        mergedSet.addAll(cachedSessionsNoTimeout.keySet());
        return mergedSet;
    }

    /**
     * Stores the initialized {@link ValidationEngine} in the cache forever. If a
     * null key is
     * passed in, a new key is generated and returned.
     * 
     * @param sessionId        The {@link String} key to associate with this stored
     *                         {@link ValidationEngine}
     * @param validationEngine The {@link ValidationEngine} instance to cache.
     * @return The {@link String} id that will be associated with the stored
     *         {@link ValidationEngine}
     */
    public synchronized String cacheSessionForEver(String sessionId, ValidationEngine validationEngine) {
        cachedSessionsNoTimeout.put(sessionId, validationEngine);
        cachedSessionIdsNoTimeout.put(validationEngine, sessionId);
        return sessionId;
    }

    public String cacheSession(String sessionId, ValidationEngine validationEngine) {
        String id = super.cacheSession(sessionId, validationEngine);
        cachedSessionIds.put(validationEngine, id);
        return sessionId;
    }

    @Override
    public boolean sessionExists(String sessionId) {
        return super.sessionExists(sessionId) || cachedSessionsNoTimeout.containsKey(sessionId);
    }

    public String getSessionId(ValidationEngine validationEngine) {
        if (cachedSessionIdsNoTimeout.containsKey(validationEngine)) {
            return cachedSessionIdsNoTimeout.get(validationEngine);
        }
        if (cachedSessionIds.containsKey(validationEngine)) {
            return cachedSessionIds.get(validationEngine);
        }
        return null;
   }

}
