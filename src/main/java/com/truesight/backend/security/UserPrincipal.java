package com.truesight.backend.security;

/**
 * What sits in Spring Security's Authentication#getPrincipal() for every request that
 * passes the JWT filter. Deliberately just an (id, email) pair, not the full User
 * entity — loading the whole JPA entity on every request would mean a database hit per
 * request just to authenticate, when the token itself already carries everything the
 * rest of the app needs to enforce ownership (AC 1.3's id+ownerId pattern only ever
 * needs the numeric id).
 */
public record UserPrincipal(Long id, String email) {
}
