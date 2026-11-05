package com.logicielpourtous.site.features.team;

import com.logicielpourtous.site.features.request.RequestType;
import com.logicielpourtous.site.features.user.User;
import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "teams")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "team_members",
            joinColumns        = @JoinColumn(name = "team_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<User> members = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "team_request_types",
            joinColumns = @JoinColumn(name = "team_id"))
    @Column(name = "request_type")
    @Enumerated(EnumType.STRING)
    private Set<RequestType> requestTypes = new HashSet<>();

    public Long getId()                          { return id; }
    public void setId(Long id)                   { this.id = id; }
    public String getName()                      { return name; }
    public void setName(String name)             { this.name = name; }
    public String getDescription()               { return description; }
    public void setDescription(String desc)      { this.description = desc; }
    public Set<User> getMembers()                { return members; }
    public void setMembers(Set<User> members)    { this.members = members; }
    public Set<RequestType> getRequestTypes()    { return requestTypes; }
    public void setRequestTypes(Set<RequestType> t) { this.requestTypes = t; }
}
