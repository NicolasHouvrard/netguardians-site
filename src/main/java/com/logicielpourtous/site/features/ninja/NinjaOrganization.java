package com.logicielpourtous.site.features.ninja;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NinjaOrganization {

    @JsonProperty("id")
    private Integer id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    public Integer getId()          { return id; }
    public String  getName()        { return name; }
    public String  getDescription() { return description; }
}
