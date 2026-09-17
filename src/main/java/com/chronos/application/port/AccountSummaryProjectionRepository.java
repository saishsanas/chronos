package com.chronos.application.port;

import com.chronos.domain.projection.AccountSummaryProjection;
import java.util.Optional;
import java.util.UUID;

public interface AccountSummaryProjectionRepository {

    Optional<AccountSummaryProjection> findByAccountId(UUID accountId);

    void save(AccountSummaryProjection projection);
}
