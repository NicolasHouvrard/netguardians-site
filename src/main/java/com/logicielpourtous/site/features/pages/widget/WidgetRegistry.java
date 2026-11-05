package com.logicielpourtous.site.features.pages.widget;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WidgetRegistry {

    private final Map<String, PublicWidget> widgets;

    public WidgetRegistry(List<PublicWidget> widgetList) {
        widgets = new LinkedHashMap<>();
        for (PublicWidget widget : widgetList) {
            widgets.put(widget.getId(), widget);
        }
    }

    public PublicWidget get(String id) {
        return widgets.get(id);
    }

    public Collection<PublicWidget> getAll() {
        return widgets.values();
    }
}
