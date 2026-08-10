package com.mopl.watchingsession.repository;

import java.util.UUID;

public interface ContentWatcherCountView {
    UUID getContentId();
    long getWatcherCount();
}