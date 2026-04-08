package com.danws.state;

import com.danws.protocol.DataType;
import com.danws.protocol.DanWSException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public class KeyRegistry {
    private static final Pattern KEY_PATH_REGEX = Pattern.compile("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)*$");

    public record KeyEntry(int keyId, String path, DataType type) {}

    private final Map<Integer, KeyEntry> byId = new LinkedHashMap<>();
    private final Map<String, KeyEntry> byPath = new LinkedHashMap<>();
    private final int initialNextId;
    private int nextId;

    public KeyRegistry() {
        this(1);
    }

    public KeyRegistry(int startingNextId) {
        this.initialNextId = startingNextId;
        this.nextId = startingNextId;
    }

    public static void validateKeyPath(String path) {
        if (path == null || path.isEmpty()) {
            throw new DanWSException("INVALID_KEY_PATH", "Key path must not be empty");
        }
        if (!KEY_PATH_REGEX.matcher(path).matches()) {
            throw new DanWSException("INVALID_KEY_PATH", "Invalid key path: \"" + path + "\"");
        }
        if (path.getBytes(StandardCharsets.UTF_8).length > 200) {
            throw new DanWSException("INVALID_KEY_PATH", "Key path exceeds 200 bytes");
        }
    }

    public KeyEntry registerOne(int keyId, String path, DataType type) {
        validateKeyPath(path);
        KeyEntry entry = new KeyEntry(keyId, path, type);
        byId.put(keyId, entry);
        byPath.put(path, entry);
        if (keyId >= nextId) nextId = keyId + 1;
        return entry;
    }

    public int registerNew(String path, DataType type) {
        validateKeyPath(path);
        int keyId = nextId++;
        KeyEntry entry = new KeyEntry(keyId, path, type);
        byId.put(keyId, entry);
        byPath.put(path, entry);
        return keyId;
    }

    /** Return the next keyId and advance the counter, without registering any entry. */
    public int nextId() {
        return nextId++;
    }

    public KeyEntry getByKeyId(int keyId) { return byId.get(keyId); }
    public KeyEntry getByPath(String path) { return byPath.get(path); }
    public boolean hasKeyId(int keyId) { return byId.containsKey(keyId); }
    public boolean hasPath(String path) { return byPath.containsKey(path); }
    public int size() { return byId.size(); }
    public List<String> paths() { return new ArrayList<>(byPath.keySet()); }
    public Collection<KeyEntry> entries() { return byId.values(); }

    public boolean remove(String path) {
        KeyEntry e = byPath.remove(path);
        if (e != null) { byId.remove(e.keyId()); return true; }
        return false;
    }

    public KeyEntry removeByKeyId(int keyId) {
        KeyEntry e = byId.remove(keyId);
        if (e != null) byPath.remove(e.path());
        return e;
    }

    public void clear() {
        byId.clear();
        byPath.clear();
        nextId = initialNextId;
    }
}
