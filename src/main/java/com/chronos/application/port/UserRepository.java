package com.chronos.application.port;

import com.chronos.domain.security.ApplicationUser;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    Optional<ApplicationUser> findByUsername(String username);

    Optional<ApplicationUser> findById(UUID userId);

    void save(ApplicationUser user);

    long count();
}
