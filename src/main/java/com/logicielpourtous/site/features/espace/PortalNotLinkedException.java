package com.logicielpourtous.site.features.espace;

public class PortalNotLinkedException extends RuntimeException {
    public PortalNotLinkedException() { super("Aucun client associé à ce compte."); }
}
