package com.logicielpourtous.site.features.intra.widget;

/** Une case montant (bg-{color} bg-opacity-10, text-{color}). color = nom Bootstrap : success, danger, warning… */
public record AmountBox(String label, String value, String color) {}
