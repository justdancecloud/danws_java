package com.danws.api;

import java.util.List;

public class TopicClientPayloadView {

    private final TopicClientHandle handle;

    TopicClientPayloadView(TopicClientHandle handle) {
        this.handle = handle;
    }

    public Object get(String key) { return handle.get(key); }
    public List<String> keys() { return handle.keys(); }
}
