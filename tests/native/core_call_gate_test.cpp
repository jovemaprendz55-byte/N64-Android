#include "core_call_gate.h"

#include <atomic>
#include <cassert>
#include <chrono>
#include <thread>

using namespace std::chrono_literals;

int main() {
    CoreCallGate gate;

    // A running EXECUTE lease remains alive while STOP is admitted.
    auto execute = gate.tryAcquire(CoreCallGate::Kind::Execute);
    assert(execute);
    assert(gate.activeCalls() == 1);

    std::atomic<bool> shutdownReturned{false};
    std::thread shutdown([&] {
        gate.beginShutdownAndWait();
        shutdownReturned.store(true);
    });

    const auto deadline = std::chrono::steady_clock::now() + 2s;
    while (!gate.shutdownRequested() &&
           std::chrono::steady_clock::now() < deadline) {
        std::this_thread::yield();
    }
    assert(gate.shutdownRequested());

    // Shutdown blocks new execution/ordinary calls but admits STOP so the
    // caller can interrupt the synchronous Core loop.
    auto rejectedExecute = gate.tryAcquire(CoreCallGate::Kind::Execute);
    auto rejectedOther = gate.tryAcquire(CoreCallGate::Kind::Other);
    assert(!rejectedExecute);
    assert(!rejectedOther);

    auto stop = gate.tryAcquire(CoreCallGate::Kind::Stop);
    assert(stop);
    assert(gate.activeCalls() == 2);
    assert(!shutdownReturned.load());

    // The drain must wait for both the synchronous EXECUTE call and the
    // concurrent STOP command to return before teardown can proceed.
    execute.release();
    assert(!shutdownReturned.load());
    stop.release();
    shutdown.join();
    assert(shutdownReturned.load());
    assert(gate.activeCalls() == 0);
    assert(!gate.tryAcquire(CoreCallGate::Kind::Stop));

    // A failed teardown can reopen admission only after the drain completed.
    assert(gate.reopenAfterFailedShutdown());
    auto ordinary = gate.tryAcquire(CoreCallGate::Kind::Other);
    assert(ordinary);
    ordinary.release();

    // Lease is move-only and releases exactly once.
    auto first = gate.tryAcquire(CoreCallGate::Kind::Execute);
    assert(first);
    CoreCallGate::Lease second(std::move(first));
    assert(!first);
    assert(second);
    second.release();
    assert(gate.activeCalls() == 0);
    return 0;
}
