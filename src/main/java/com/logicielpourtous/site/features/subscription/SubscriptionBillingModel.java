package com.logicielpourtous.site.features.subscription;

public enum SubscriptionBillingModel {

    FORFAIT   ("Forfait",            "fas fa-cubes",        false),
    HOURLY    ("À l'heure",          "fas fa-clock",        false),
    PER_UNIT  ("Par unité",          "fas fa-boxes-stacked",false),
    PER_USER  ("Par personne",       "fas fa-users",        true),
    PER_DEVICE("Par PC / appareil",  "fas fa-desktop",      true);

    private final String label;
    private final String icon;
    /** true = le nombre sera synchronisé depuis un module futur (contacts, appareils) */
    private final boolean autoSync;

    SubscriptionBillingModel(String label, String icon, boolean autoSync) {
        this.label    = label;
        this.icon     = icon;
        this.autoSync = autoSync;
    }

    public String  getLabel()    { return label;    }
    public String  getIcon()     { return icon;     }
    public boolean isAutoSync()  { return autoSync; }
}
