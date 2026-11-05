package com.logicielpourtous.site.features.intra.widget;

/** Un badge de compteur. badgeClass = classe Bootstrap complète ex: text-bg-success, text-bg-warning text-dark */
public record StatusBadge(String label, String value, String badgeClass) {}
