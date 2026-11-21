package com.aid.assistant.realtime;

import com.aid.runtime.domain.AidRuntimeEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Publishes committed assistant runtime events to realtime subscribers. */
@Component
@RequiredArgsConstructor
public class AssistantRealtimeEventPublisher
{
    private final AssistantRealtimeEventHub eventHub;

    /** Shared Runtime journal writers publish internal events in their persistence transaction. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishCommittedRuntimeEvent(AidRuntimeEvent event)
    {
        eventHub.publish(durableSignal(event));
    }

    public void publishAfterCommit(AidRuntimeEvent event)
    {
        AssistantRealtimeSignal signal = durableSignal(event);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit()
                {
                    eventHub.publish(signal);
                }
            });
            return;
        }
        eventHub.publish(signal);
    }

    private AssistantRealtimeSignal durableSignal(AidRuntimeEvent event)
    {
        AssistantRealtimeSignal signal = new AssistantRealtimeSignal();
        signal.setType("RUNTIME_EVENT");
        signal.setEventId(event.getId());
        signal.setSessionId(event.getSessionId());
        signal.setSeq(event.getSeq());
        signal.setRunId(event.getRunId());
        signal.setEventType(event.getEventType());
        signal.setEntityId(event.getEntityId());
        signal.setEntityRevision(event.getEntityRevision());
        signal.setCausationId(event.getCausationId());
        signal.setPayloadJson(event.getPayloadJson());
        signal.setCreateTime(event.getCreateTime());
        return signal;
    }
}
