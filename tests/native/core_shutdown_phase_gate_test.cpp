#include "core_shutdown_phase_gate.h"

#include <atomic>
#include <cassert>
#include <iostream>
#include <thread>
#include <vector>

int main() {
    CoreShutdownPhaseGate gate;
    auto token = gate.beginPrepare();
    assert(token);
    assert(gate.phase() == CoreShutdownPhaseGate::Phase::Preparing);

    // Concurrent prepare requests must join one generation.
    std::atomic<bool> start{false};
    std::vector<std::uint64_t> generations(16, 0);
    std::vector<std::thread> preparers;
    for (std::size_t i = 0; i < generations.size(); ++i) {
        preparers.emplace_back([&, i] {
            while (!start.load()) std::this_thread::yield();
            generations[i] = gate.beginPrepare().generation;
        });
    }
    start.store(true);
    for (auto& thread : preparers) thread.join();
    for (const auto generation : generations)
        assert(generation == token.generation);

    // Joining the generation does not grant ownership. Exactly one worker
    // may claim the STOP/drain preparation operation.
    std::atomic<bool> claimStart{false};
    std::atomic<int> claimWinners{0};
    std::vector<std::thread> claimers;
    for (int i = 0; i < 16; ++i) {
        claimers.emplace_back([&] {
            while (!claimStart.load()) std::this_thread::yield();
            if (gate.tryClaimPrepare(token)) ++claimWinners;
        });
    }
    claimStart.store(true);
    for (auto& thread : claimers) thread.join();
    assert(claimWinners.load() == 1);
    assert(!gate.tryClaimPrepare(token));

    // A failed prepare releases its claim without changing the generation,
    // allowing one later worker to retry safely.
    assert(gate.releasePrepareClaim(token));
    assert(!gate.releasePrepareClaim(token));
    assert(gate.tryClaimPrepare(token));
    assert(!gate.releasePrepareClaim({token.generation + 1}));
    assert(gate.markDrained(token));
    assert(!gate.markDrained(token));

    // Finalization is forbidden until all preconditions are true.
    assert(!gate.beginFinalize(token, false, true, true));
    assert(!gate.beginFinalize(token, true, false, true));
    assert(!gate.beginFinalize(token, true, true, false));
    assert(gate.phase() == CoreShutdownPhaseGate::Phase::Drained);

    assert(gate.beginFinalize(token, true, true, true));
    assert(!gate.resetAfterComplete()); // cannot reset while finalizing
    assert(!gate.beginFinalize(token, true, true, true)); // no parallel finalize
    assert(gate.finishFinalize(token, false)); // retry state after partial failure
    assert(gate.phase() == CoreShutdownPhaseGate::Phase::Drained);

    // Concurrent finalizers: exactly one may acquire the finalization phase.
    std::atomic<bool> finalizeStart{false};
    std::atomic<int> finalizeWinners{0};
    std::vector<std::thread> finalizers;
    for (int i = 0; i < 16; ++i) {
        finalizers.emplace_back([&] {
            while (!finalizeStart.load()) std::this_thread::yield();
            if (gate.beginFinalize(token, true, true, true))
                ++finalizeWinners;
        });
    }
    finalizeStart.store(true);
    for (auto& thread : finalizers) thread.join();
    assert(finalizeWinners.load() == 1);
    assert(gate.finishFinalize(token, true));
    assert(gate.phase() == CoreShutdownPhaseGate::Phase::Complete);
    assert(!gate.beginPrepare()); // completed generation cannot be reused

    // Recovery is explicit and only follows a fully completed generation.
    assert(gate.resetAfterComplete());
    auto next = gate.beginPrepare();
    assert(next);
    assert(next.generation != token.generation);
    assert(!gate.markDrained(token)); // stale callback from old generation
    assert(gate.tryClaimPrepare(next));
    assert(gate.markDrained(next));

    std::cout << "Core shutdown phase gate tests passed\n";
    return 0;
}
