package com.logicielpourtous.site.features.site;

public interface Navigable {
    Long getId();
    int getOrder();
    void setOrder(int order);
    String getMenuType(); // Déjà présent dans ton code
}
