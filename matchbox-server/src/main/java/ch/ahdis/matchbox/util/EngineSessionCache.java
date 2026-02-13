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
 *         We want to have a validation engines also that are not timed out as
 *         in the parent class.
 *         
 *         Note: To prevent memory leaks, the "forever" caches use an LRU eviction policy
 *         with a maximum size limit. When the limit is reached, the least recently used
 *         entries are automatically removed.
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
        
        // Create LRU caches with automatic eviction to prevent memory leaks
        // Use a single map to avoid bidirectional synchronization issues
        this.cachedSessionsNoTimeout = Collections.synchronizedMap(
            new LinkedHashMap<String, ValidationEngine>(MAX_PERMANENT_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ValidationEngine> eldest) {
                    return size() > MAX_PERMANENT_CACHE_SIZE;
                }
            }
        );
        
        this.cachedSessionIdsNoTimeout = Collections.synchronizedMap(
            new LinkedHashMap<ValidationEngine, String>(MAX_PERMANENT_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ValidationEngine, String> eldest) {
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
        // Clean up any orphaned entries due to LRU eviction
        cleanupOrphanedEntries();
        return sessionId;
    }
    
    /**
     * Cleans up orphaned entries in bidirectional maps after LRU eviction.
     * This ensures map consistency when one map evicts entries but the other doesn't.
     */
    private void cleanupOrphanedEntries() {
        // Remove entries from cachedSessionIdsNoTimeout that don't have corresponding entries in cachedSessionsNoTimeout
        cachedSessionIdsNoTimeout.entrySet().removeIf(entry -> 
            !cachedSessionsNoTimeout.containsValue(entry.getKey())
        );
        // Remove entries from cachedSessionsNoTimeout that don't have corresponding entries in cachedSessionIdsNoTimeout
        cachedSessionsNoTimeout.entrySet().removeIf(entry -> 
            !cachedSessionIdsNoTimeout.containsKey(entry.getValue())
        );
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
