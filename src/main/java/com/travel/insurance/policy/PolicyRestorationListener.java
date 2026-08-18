package com.travel.insurance.policy;

import com.travel.insurance.insurer.Insurer;
import com.travel.insurance.insurer.InsurerRepository;
import com.travel.insurance.visitor.VisitorDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restores the policy token for a policy's backing insurer when a visitor is deleted
 * (soft-deleted). When a visitor is removed from the system, the backing insurer's
 * policy count is incremented by 1, making that policy available for other travelers.
 *
 * This maintains the integrity of the policy quota system by ensuring that
 * deleted visitors don't permanently consume policies.
 *
 * Example:
 * - Minet Insurance has 999 policies (previously had 1000, used 1)
 * - A visitor using a Minet-backed policy is deleted
 * - Minet's policyToken becomes 1000 (restored)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PolicyRestorationListener {

    private final PolicyRepository policyRepository;
    private final InsurerRepository insurerRepository;

    /**
     * Listens for visitor deletion events and restores the policy token
     * for the backing insurer of the policy the visitor was using.
     *
     * @param event the visitor deletion event containing visitor ID and policy ID
     */
    @EventListener
    @Transactional
    public void onVisitorDeleted(VisitorDeletedEvent event) {
        Policy policy = policyRepository.findById(event.policyId())
                .orElseThrow(() -> new IllegalStateException(
                        "Policy not found: " + event.policyId()));

        Insurer insurer = insurerRepository.findById(policy.getInsurerId())
                .orElseThrow(() -> new IllegalStateException(
                        "Insurer not found: " + policy.getInsurerId()));

        if (insurer.getPolicyToken() != null) {
            long newToken = insurer.getPolicyToken() + 1;
            insurer.setPolicyToken(newToken);
            insurerRepository.save(insurer);
            log.info("Policy restored for insurer: {}. Available tokens: {}",
                    insurer.getName(), newToken);
        } else {
            // Initialize token to 1 if it was null
            insurer.setPolicyToken(1L);
            insurerRepository.save(insurer);
            log.info("Policy token initialized for insurer: {}. Available tokens: 1",
                    insurer.getName());
        }
    }
}
