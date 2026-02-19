# Memory Leak Prevention in Matchbox

## How LinkedHashMap Prevents Memory Leaks

### Important: LinkedHashMap Alone Does NOT Prevent Memory Leaks

A common misconception is that simply changing a `HashMap` to a `LinkedHashMap` prevents memory leaks. **This is incorrect.** Both `HashMap` and `LinkedHashMap` will grow unboundedly if entries are continuously added without removal.

### The Key: removeEldestEntry() Override

What actually prevents memory leaks is the **`removeEldestEntry()` method override** combined with LinkedHashMap's ordering capabilities. Here's how it works:

#### 1. LinkedHashMap's Special Features

LinkedHashMap extends HashMap but maintains a doubly-linked list of entries in addition to the hash table. This allows it to maintain:
- **Insertion order** (default): Entries are ordered by when they were first inserted
- **Access order** (when constructed with `accessOrder = true`): Entries are ordered by when they were last accessed

#### 2. The removeEldestEntry() Hook

LinkedHashMap provides a protected method that is called after each `put()` operation:

```java
protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
    return false; // Default implementation never removes entries
}
```

By overriding this method to return `true` when a size limit is exceeded, we can implement automatic eviction:

```java
new LinkedHashMap<String, ValidationEngine>(initialCapacity, loadFactor, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, ValidationEngine> eldest) {
        return size() > MAX_PERMANENT_CACHE_SIZE; // Evict when size exceeds limit
    }
}
```

#### 3. LRU (Least Recently Used) Eviction Policy

When `accessOrder = true` (the third parameter in the constructor):
- Each `get()` operation moves the accessed entry to the end of the linked list
- The `eldest` entry (first in the linked list) is the least recently used
- When `removeEldestEntry()` returns true, this eldest entry is automatically removed

This creates an **LRU cache** that automatically evicts the least recently used entries when the size limit is reached.

## Implementations in Matchbox

### 1. EngineSessionCache

**Location**: `matchbox-server/src/main/java/ch/ahdis/matchbox/util/EngineSessionCache.java`

**Problem**: ValidationEngine instances are heavy objects. Without bounds, the cache could grow indefinitely, consuming all available memory.

**Solution**:
```java
private static final int MAX_PERMANENT_CACHE_SIZE = 50;

this.cachedSessionsNoTimeout = Collections.synchronizedMap(
    new LinkedHashMap<String, ValidationEngine>(MAX_PERMANENT_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ValidationEngine> eldest) {
            return size() > MAX_PERMANENT_CACHE_SIZE;
        }
    }
);
```

**Key aspects**:
- `MAX_PERMANENT_CACHE_SIZE = 50`: Limits the cache to 50 entries
- `true`: Enables access-order mode for LRU behavior
- `Collections.synchronizedMap()`: Adds thread safety
- Automatically removes least recently used ValidationEngine when limit is exceeded

### 2. TxValidationCache

**Location**: `matchbox-server/src/main/java/ch/ahdis/matchbox/terminology/validation/TxValidationCache.java`

**Problem**: ValueSet expansions can be large. Without bounds, each cache ID could accumulate unlimited expansions.

**Solution**:
```java
private static final int MAX_VALUESETS_PER_CACHE_ID = 100;

private Map<String, ValueSet> createBoundedLinkedHashMap() {
    return new LinkedHashMap<String, ValueSet>(20, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ValueSet> eldest) {
            return size() > MAX_VALUESETS_PER_CACHE_ID;
        }
    };
}
```

**Key aspects**:
- Each cache ID gets its own bounded LinkedHashMap
- Maximum 100 ValueSet expansions per cache ID
- LRU eviction ensures most frequently used expansions are retained

### 3. JpaPackageCache Processing Messages

**Location**: `matchbox-server/src/main/java/ca/uhn/fhir/jpa/packages/JpaPackageCache.java`

**Problem**: Processing messages accumulate in ArrayLists without bounds.

**Solution**: While not using LinkedHashMap (since it's a List, not a Map), the same principle applies:
```java
private static final int MAX_PROCESSING_MESSAGES = 100;

public static void addProcessingMessage(NpmPackage thePackage, String message) {
    List<String> messages = getProcessingMessages(thePackage);
    messages.add(message);
    
    // Trim old messages if list is too large
    if (messages.size() > MAX_PROCESSING_MESSAGES) {
        messages.subList(0, messages.size() - MAX_PROCESSING_MESSAGES).clear();
    }
}
```

## Why This Pattern Works

### Without Bounds (Memory Leak)
```
Time →
Cache size: 10 → 50 → 100 → 500 → 1000 → ... → OutOfMemoryError
```

### With LRU Eviction (No Memory Leak)
```
Time →
Cache size: 10 → 50 → 50 → 50 → 50 → ... (stays at or below 50)
                    ↑ Oldest entry removed when adding new entry
```

## Constructor Parameters Explained

```java
new LinkedHashMap<K, V>(initialCapacity, loadFactor, accessOrder)
```

- **initialCapacity**: Initial size of the hash table (e.g., 50)
- **loadFactor**: Threshold for resizing (0.75f means resize when 75% full)
- **accessOrder**: 
  - `true` = access-order (LRU): `get()` moves entry to end
  - `false` = insertion-order: order based on first insertion

## Common Pitfalls

### ❌ Wrong: Just changing HashMap to LinkedHashMap
```java
// This DOES NOT prevent memory leaks!
Map<String, Object> cache = new LinkedHashMap<>();
```

### ❌ Wrong: LinkedHashMap without removeEldestEntry override
```java
// This DOES NOT prevent memory leaks!
Map<String, Object> cache = new LinkedHashMap<>(100, 0.75f, true);
```

### ✅ Correct: LinkedHashMap with removeEldestEntry override
```java
// This DOES prevent memory leaks!
Map<String, Object> cache = new LinkedHashMap<>(100, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
        return size() > 100;
    }
};
```

## Thread Safety Considerations

LinkedHashMap is not thread-safe by default. For concurrent access:

```java
Map<String, Object> cache = Collections.synchronizedMap(
    new LinkedHashMap<>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > 100;
        }
    }
);
```

Or use `ConcurrentHashMap` with manual eviction logic for better concurrent performance.

## Performance Characteristics

| Operation | HashMap | LinkedHashMap |
|-----------|---------|---------------|
| get()     | O(1)    | O(1)          |
| put()     | O(1)    | O(1)          |
| remove()  | O(1)    | O(1)          |
| iteration | O(capacity) | O(size)   |

LinkedHashMap has slightly higher memory overhead due to maintaining the doubly-linked list, but provides O(size) iteration instead of O(capacity), and enables the LRU eviction pattern.

## Testing the Implementation

To verify the LRU cache is working:

1. Add more than MAX_SIZE entries
2. Verify the cache size never exceeds MAX_SIZE
3. Access an old entry (moving it to end of LRU list)
4. Add a new entry
5. Verify the least recently used entry was evicted, not the recently accessed one

## References

- [Java LinkedHashMap Documentation](https://docs.oracle.com/javase/8/docs/api/java/util/LinkedHashMap.html)
- [Effective Java, 3rd Edition - Item 9: Prefer try-with-resources to try-finally](https://www.oreilly.com/library/view/effective-java/9780134686097/)
- [LRU Cache Implementation Pattern](https://en.wikipedia.org/wiki/Cache_replacement_policies#Least_recently_used_(LRU))
