package com.logicielpourtous.site.features.api.mobile.dto;

import java.util.List;

public record LoginResponse(String token, String email, String fullName, List<String> roles) {}
