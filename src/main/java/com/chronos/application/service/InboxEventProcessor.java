package com.chronos.application.service;

import com.chronos.application.port.ConsumerEventIntegrityException;
import com.chronos.application.port.DownstreamEventConsumer;
import com.chronos.application.port.InboxRepository;
import com.chronos.application.port.InvalidEventEnvelopeException;
import com.chronos.domain.event.DomainEventEnvelope;
import com.chronos.domain.inbox.InboxEventRecord;
import com.chronos.domain.inbox.InboxStatus;
import com.chronos.infrastructure.observability.ChronosMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class InboxEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboxEventProcessor.class);

    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
        "AccountCreated",
        "MoneyDeposited",
        "MoneyWithdrawn",
        "AccountFrozen",
        "AccountUnfrozen",
        "OverdraftLimitChanged",
        "TransactionLimitChanged",
        "CorrectionIssued",
        "AccountClosed"
    );

    private final InboxRepository inboxRepository;
    private final DownstreamEventConsumer downstreamEventConsumer;
    private final ChronosMetrics metrics;

    public enum ProcessResult {
        PROCESSED,
        DUPLICATE,
        QUARANTINED
    }

    public InboxEventProcessor(
        InboxRepository inboxRepository,
        @Autowired(required = false) DownstreamEventConsumer downstreamEventConsumer
    ) {
        this(inboxRepository, downstreamEventConsumer, null);
    }

    @Autowired
    public InboxEventProcessor(
        InboxRepository inboxRepository,
        @Autowired(required = false) DownstreamEventConsumer downstreamEventConsumer,
        @Autowired(required = false) ChronosMetrics metrics
    ) {
        this.inboxRepository = Objects.requireNonNull(inboxRepository, "inboxRepository must not be null");
        this.downstreamEventConsumer = downstreamEventConsumer;
        this.metrics = metrics;
    }

    @Transactional(noRollbackFor = InvalidEventEnvelopeException.class)
    public ProcessResult process(DomainEventEnvelope envelope) {
        // 1. Validate envelope integrity with poison event quarantine
        try {
            validateEnvelope(envelope);
        } catch (InvalidEventEnvelopeException e) {
            log.error("Poison/Malformed event detected: {}. Quarantining event.", e.getMessage());
            if (metrics != null) {
                metrics.recordInboxPoison();
            }
            if (envelope != null && envelope.eventId() != null) {
                Optional<InboxEventRecord> existing = inboxRepository.findByEventId(envelope.eventId());
                if (existing.isPresent()) {
                    inboxRepository.markQuarantined(existing.get().inboxId(), e.getMessage());
                } else {
                    inboxRepository.save(InboxEventRecord.createQuarantined(envelope, e.getMessage()));
                }
            }
            throw e;
        }

        // 2. Check for duplicate/quarantined eventId in inbox
        Optional<InboxEventRecord> existing = inboxRepository.findByEventId(envelope.eventId());
        if (existing.isPresent()) {
            if (existing.get().status() == InboxStatus.PROCESSED) {
                log.info("Duplicate event detected (eventId: {}). Ignoring business effect.", envelope.eventId());
                if (metrics != null) metrics.recordInboxDuplicate();
                return ProcessResult.DUPLICATE;
            } else if (existing.get().status() == InboxStatus.QUARANTINED) {
                log.warn("Quarantined poison event encountered (eventId: {}). Skipping processing.", envelope.eventId());
                if (metrics != null) metrics.recordInboxPoison();
                return ProcessResult.QUARANTINED;
            }
        }

        // 3. Sequence integrity check
        long lastProcessedSeq = inboxRepository.getLastProcessedSequence(envelope.aggregateId());
        long expectedSeq = lastProcessedSeq == 0 ? 1L : lastProcessedSeq + 1L;

        if (lastProcessedSeq == 0) {
            if (envelope.sequenceNumber() != 1L) {
                throw new ConsumerEventIntegrityException(
                    "Initial sequence for aggregate " + envelope.aggregateId() + " must be 1 but got " + envelope.sequenceNumber()
                );
            }
        } else {
            if (envelope.sequenceNumber() < expectedSeq) {
                throw new ConsumerEventIntegrityException(
                    "Sequence regression/collision for aggregate " + envelope.aggregateId() +
                    ": received sequence " + envelope.sequenceNumber() + " but expected " + expectedSeq + " (last processed: " + lastProcessedSeq + ")"
                );
            } else if (envelope.sequenceNumber() > expectedSeq) {
                throw new ConsumerEventIntegrityException(
                    "Sequence gap detected for aggregate " + envelope.aggregateId() +
                    ": received sequence " + envelope.sequenceNumber() + " but expected " + expectedSeq
                );
            }
        }

        // 4. Record inbox entry
        InboxEventRecord record = existing.orElseGet(() -> inboxRepository.save(InboxEventRecord.fromEnvelope(envelope)));

        // 5. Invoke downstream business processing
        try {
            if (downstreamEventConsumer != null) {
                downstreamEventConsumer.onEvent(envelope);
            }

            // 6. Mark inbox record as PROCESSED & update consumer aggregate sequence state
            Instant now = Instant.now();
            inboxRepository.markProcessed(record.inboxId(), now);
            inboxRepository.updateConsumerSequence(envelope.aggregateId(), envelope.sequenceNumber(), now);

            log.info("Successfully processed inbox event (outboxId: {}, eventId: {}, aggregateId: {}, sequence: {})",
                record.inboxId(), envelope.eventId(), envelope.aggregateId(), envelope.sequenceNumber());
            if (metrics != null) metrics.recordInboxProcessed();
            return ProcessResult.PROCESSED;

        } catch (Exception e) {
            log.error("Failed to execute downstream processing for eventId {}: {}", envelope.eventId(), e.getMessage());
            inboxRepository.markFailed(record.inboxId(), e.getMessage());
            if (metrics != null) metrics.recordInboxFailed();
            throw e;
        }
    }

    private void validateEnvelope(DomainEventEnvelope envelope) {
        if (envelope == null) {
            throw new InvalidEventEnvelopeException("DomainEventEnvelope must not be null");
        }
        if (envelope.eventId() == null) {
            throw new InvalidEventEnvelopeException("eventId must not be null");
        }
        if (envelope.aggregateId() == null) {
            throw new InvalidEventEnvelopeException("aggregateId must not be null");
        }
        if (!"Account".equals(envelope.aggregateType())) {
            throw new InvalidEventEnvelopeException("Unsupported aggregateType: " + envelope.aggregateType());
        }
        if (envelope.sequenceNumber() <= 0) {
            throw new InvalidEventEnvelopeException("sequenceNumber must be greater than 0: " + envelope.sequenceNumber());
        }
        if (envelope.eventType() == null || !SUPPORTED_EVENT_TYPES.contains(envelope.eventType())) {
            throw new InvalidEventEnvelopeException("Unsupported or unknown eventType: " + envelope.eventType());
        }
        if (envelope.eventVersion() != 1) {
            throw new InvalidEventEnvelopeException("Unsupported eventVersion: " + envelope.eventVersion());
        }
        if (envelope.metadata() == null) {
            throw new InvalidEventEnvelopeException("Event metadata must not be null");
        }
        if (envelope.payload() == null || envelope.payload().isEmpty()) {
            throw new InvalidEventEnvelopeException("Event payload must not be null or empty");
        }
    }
}
