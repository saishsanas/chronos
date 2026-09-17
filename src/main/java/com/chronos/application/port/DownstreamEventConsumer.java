package com.chronos.application.port;

import com.chronos.domain.event.DomainEventEnvelope;

public interface DownstreamEventConsumer {

    void onEvent(DomainEventEnvelope envelope);
}
