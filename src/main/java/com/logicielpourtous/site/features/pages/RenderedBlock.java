package com.logicielpourtous.site.features.pages;

import java.util.Map;

public class RenderedBlock {

    private final PageBlock block;
    private final Map<String, Object> content;
    private final String widgetHtml;

    public RenderedBlock(PageBlock block, Map<String, Object> content, String widgetHtml) {
        this.block = block;
        this.content = content;
        this.widgetHtml = widgetHtml;
    }

    public PageBlock getBlock() { return block; }

    public Map<String, Object> getContent() { return content; }

    public String getWidgetHtml() { return widgetHtml; }

    /**
     * Convenience accessor: returns the string value of a content key, or "" if absent.
     */
    public String get(String key) {
        return String.valueOf(content.getOrDefault(key, ""));
    }

    /**
     * Convenience accessor: returns the string value of a content key, or defaultValue if absent/empty.
     */
    public String get(String key, String defaultValue) {
        Object val = content.get(key);
        if (val == null || val.toString().isBlank()) {
            return defaultValue;
        }
        return String.valueOf(val);
    }
}
