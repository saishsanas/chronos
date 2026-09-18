package com.chronos.application.port;

import com.chronos.domain.projection.AccountSummaryProjection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountSummaryProjectionRepository {

    Optional<AccountSummaryProjection> findByAccountId(UUID accountId);

    List<AccountSummaryProjection> findAll();

    void save(AccountSummaryProjection projection);

    void deleteByAccountId(UUID accountId);

    void prepareStagingTable();

    void saveToStaging(List<AccountSummaryProjection> projections);

    void swapStagingToLive();
}
