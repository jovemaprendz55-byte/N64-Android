#include "core_runtime_coordinator.h"

#include <cassert>
#include <chrono>
#include <thread>

int main() {
    using Coordinator = CoreRuntimeCoordinator;
    using Phase = Coordinator::Phase;
    using Operation = Coordinator::Operation;

    Coordinator runtime;
    assert(runtime.phase() == Phase::Empty);
    assert(!runtime.tryAcquire(Operation::Execute));
    assert(runtime.markCoreInitialized());
    assert(runtime.markRomOpen());
    assert(runtime.markPluginsAttached());

    auto execute = runtime.tryAcquire(Operation::Execute);
    assert(execute);
    assert(runtime.markExecuting());
    // Shutdown closes ordinary admission while Execute is still in flight.
    assert(runtime.beginShutdown());
    assert(runtime.phase() == Phase::StopRequested);
    // When synchronous Execute finally returns, it must not reopen admission.
    assert(runtime.markExecuteReturned());
    assert(runtime.phase() == Phase::StopRequested);
    assert(!runtime.tryAcquire(Operation::Execute));
    assert(!runtime.tryAcquire(Operation::Other));
    auto stop = runtime.tryAcquire(Operation::Stop);
    assert(stop);
    assert(runtime.activeCalls() == 2);
    assert(!runtime.markShutdownComplete());

    bool drained = false;
    std::thread waiter([&] {
        drained = runtime.waitForDrainFor(std::chrono::milliseconds(500));
    });

    execute.release();
    stop.release();
    waiter.join();

    assert(drained);
    assert(runtime.activeCalls() == 0);
    assert(runtime.phase() == Phase::Drained);
    assert(!runtime.tryAcquire(Operation::Other));
    assert(runtime.markShutdownComplete());
    assert(runtime.phase() == Phase::ShutdownComplete);
    assert(!runtime.beginShutdown());
    assert(!runtime.markCoreInitialized());

    // A bounded timeout leaves the coordinator in StopRequested and keeps
    // outstanding ownership visible; no caller may mark teardown complete.
    Coordinator timeoutRuntime;
    assert(timeoutRuntime.markCoreInitialized());
    assert(timeoutRuntime.markRomOpen());
    assert(timeoutRuntime.markPluginsAttached());
    auto heldOther = timeoutRuntime.tryAcquire(Operation::Other);
    assert(heldOther);
    assert(timeoutRuntime.beginShutdown());
    auto timeoutStop = timeoutRuntime.tryAcquire(Operation::Stop);
    assert(timeoutStop);
    timeoutStop.release();
    assert(!timeoutRuntime.waitForDrainFor(std::chrono::milliseconds(2)));
    assert(timeoutRuntime.phase() == Phase::StopRequested);
    assert(timeoutRuntime.activeCalls() == 1);
    assert(!timeoutRuntime.markShutdownComplete());
    heldOther.release();
    assert(timeoutRuntime.waitForDrainFor(std::chrono::milliseconds(100)));
    assert(timeoutRuntime.phase() == Phase::Drained);
    assert(timeoutRuntime.markShutdownComplete());
    return 0;
}
