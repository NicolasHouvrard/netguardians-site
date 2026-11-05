package com.logicielpourtous.site.features.request;

import java.time.LocalDateTime;

public record TimelineEvent(LocalDateTime when, String type, Comment comment, TimeEntry timeEntry) {}
