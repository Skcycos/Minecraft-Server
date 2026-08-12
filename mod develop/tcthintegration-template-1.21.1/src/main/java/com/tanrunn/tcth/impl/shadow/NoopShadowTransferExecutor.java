package com.tanrunn.tcth.impl.shadow;

/**
 * Production-safe default {@link ShadowTransferExecutor} (8B / 8B.1 / 8C.1).
 *
 * <p>Fails at every stage and never commits anything: no item, coin, health,
 * hunger or effect is ever moved by the production wiring, even if every
 * config switch is flipped to {@code true}. The real player-asset engine
 * ({@link PlayerAssetTransferExecutor}) exists but is deliberately NOT wired
 * into {@link ShadowAttemptCoordinator#defaults()} in phase 8C.1.
 */
public final class NoopShadowTransferExecutor implements ShadowTransferExecutor {

    public static final NoopShadowTransferExecutor INSTANCE = new NoopShadowTransferExecutor();

    public static final String REASON = "transfer_executor_not_implemented";

    private NoopShadowTransferExecutor() {
    }

    @Override
    public ShadowTransferPlan prepare(ShadowAttemptContext context, ShadowCandidate selected,
                                      net.minecraft.util.RandomSource random) {
        return null; // fail-closed: no transfer can be planned
    }

    @Override
    public ShadowTransferResult commit(ShadowAttemptContext context, ShadowCandidate selected,
                                       ShadowTransferPlan plan) {
        return ShadowTransferResult.failed(REASON);
    }

    @Override
    public boolean rollback(ShadowAttemptContext context, ShadowCandidate selected, ShadowTransferPlan plan) {
        return false;
    }
}
