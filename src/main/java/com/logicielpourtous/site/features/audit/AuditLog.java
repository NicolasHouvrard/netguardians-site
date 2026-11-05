package com.logicielpourtous.site.features.audit;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_timestamp", columnList = "timestamp DESC"),
    @Index(name = "idx_audit_user_id",   columnList = "user_id"),
    @Index(name = "idx_audit_type",      columnList = "type")
})
public class AuditLog {

    public enum Type { LOGIN_SUCCESS, LOGIN_FAILURE, PAGE_VISIT }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 255)
    private String email;

    @Column(length = 45)
    private String ip;

    @Column(length = 100)
    private String country;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(length = 100)
    private String city;

    @Column(length = 500)
    private String url;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();

    // Getters / Setters
    public Long getId()                  { return id; }
    public Type getType()                { return type; }
    public void setType(Type type)       { this.type = type; }
    public Long getUserId()              { return userId; }
    public void setUserId(Long userId)   { this.userId = userId; }
    public String getEmail()             { return email; }
    public void setEmail(String email)   { this.email = email; }
    public String getIp()                { return ip; }
    public void setIp(String ip)         { this.ip = ip; }
    public String getCountry()           { return country; }
    public void setCountry(String c)     { this.country = c; }
    public String getCountryCode()       { return countryCode; }
    public void setCountryCode(String c) { this.countryCode = c; }
    public String getCity()              { return city; }
    public void setCity(String c)        { this.city = c; }
    public String getUrl()               { return url; }
    public void setUrl(String url)       { this.url = url; }
    public String getUserAgent()         { return userAgent; }
    public void setUserAgent(String ua)  { this.userAgent = ua != null && ua.length() > 500 ? ua.substring(0, 500) : ua; }
    public LocalDateTime getTimestamp()  { return timestamp; }
    public void setTimestamp(LocalDateTime t) { this.timestamp = t; }
}
