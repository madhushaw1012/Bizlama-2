package com.bizlama.api.auth;

import java.util.Collection;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

final class JwtRoleAuthoritiesConverter
        implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final WorkspaceAccessPolicy accessPolicy;

    JwtRoleAuthoritiesConverter(WorkspaceAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        return accessPolicy.effectiveRole(jwt)
                .<Collection<GrantedAuthority>>map(role -> List.of(
                        new SimpleGrantedAuthority("ROLE_" + role)
                ))
                .orElseGet(List::of);
    }
}
