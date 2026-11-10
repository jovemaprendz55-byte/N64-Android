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
    assert(!gate.beginFinalize(token, true, true, true)); // no parallel finalize
    assert(gate.finishFinalize(token, false)); // safe retry state
    assert(gate.phase() == CoreShutdownPhaseGate::Phase::Drained);

    assert(gate.beginFinalize(token, true, true, true));
    assert(gate.finishFinalize(token, true));
    assert(gate.phase() == CoreShutdownPhaseGate::Phase::Complete);
    assert(!gate.beginPrepare()); // completed generation cannot be reused

    // Stale callbacks cannot mutate a later generation. A later generation
    // is only available after an explicit future reset/recovery API exists.
    assert(!gate.markDrained({token.generation + 1}));
    std::cout << "Core shutdown phase gate tests passed\n";
    return 0;
}
