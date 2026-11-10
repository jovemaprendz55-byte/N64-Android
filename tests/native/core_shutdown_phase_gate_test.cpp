#include "core_shutdown_phase_gate.h"

#include <cassert>
#include <iostream>

int main() {
    CoreShutdownPhaseGate gate;
    auto token = gate.beginPrepare();
    assert(token);
    assert(gate.phase() == CoreShutdownPhaseGate::Phase::Preparing);

    // Preparation is single-flight and returns the same generation.
    auto same = gate.beginPrepare();
    assert(same.generation == token.generation);
    assert(gate.markDrained(token));
    assert(!gate.markDrained(token));

    // Finalization is forbidden until every precondition is explicitly true.
    assert(!gate.beginFinalize(token, false, true, true));
    assert(!gate.beginFinalize(token, true, false, true));
    assert(!gate.beginFinalize(token, true, true, false));
    assert(gate.phase() == CoreShutdownPhaseGate::Phase::Drained);

    assert(gate.beginFinalize(token, true, true, true));
    assert(!gate.resetAfterComplete()); // cannot reset while finalizing
    assert(!gate.beginFinalize(token, true, true, true)); // no parallel finalize
    assert(gate.finishFinalize(token, false)); // safe retry state
    assert(gate.phase() == CoreShutdownPhaseGate::Phase::Drained);

    assert(gate.beginFinalize(token, true, true, true));
    assert(gate.finishFinalize(token, true));
    assert(gate.phase() == CoreShutdownPhaseGate::Phase::Complete);
    assert(!gate.beginPrepare()); // completed generation cannot be reused

    // Recovery is explicit and only follows a fully completed generation.
    assert(gate.resetAfterComplete());
    auto next = gate.beginPrepare();
    assert(next);
    assert(next.generation != token.generation);
    assert(!gate.markDrained(token)); // stale callback from old generation
    assert(gate.markDrained(next));

    std::cout << "Core shutdown phase gate tests passed\n";
    return 0;
}
