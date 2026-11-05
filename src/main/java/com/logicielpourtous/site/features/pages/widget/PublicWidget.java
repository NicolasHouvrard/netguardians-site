package com.logicielpourtous.site.features.pages.widget;

import java.util.Map;

public interface PublicWidget {

    String getId();

    String getLabel();

    String getDescription();

    Map<String, Object> getData(Map<String, String> params);
}
