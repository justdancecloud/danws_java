package com.danws.api;

@FunctionalInterface
public interface TopicCallback {
    void accept(EventType event, TopicHandle topic, DanWebSocketSession session);
}
