package com.danws.api;

import java.util.*;

/**
 * Defensive deep copy for values returned to user code.
 * Immutable types (String, Number, Boolean) are returned as-is.
 */
@SuppressWarnings("unchecked")
class DeepCopy {

    private DeepCopy() {}

    static Object copy(Object val) {
        if (val == null) return null;
        if (val instanceof String || val instanceof Number || val instanceof Boolean) return val;
        if (val instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(copy(item));
            return Collections.unmodifiableList(result);
        }
        if (val instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>(map.size());
            for (var e : ((Map<String, Object>) map).entrySet()) {
                result.put(e.getKey(), copy(e.getValue()));
            }
            return Collections.unmodifiableMap(result);
        }
        if (val instanceof byte[] bytes) return bytes.clone();
        return val;
    }
}
