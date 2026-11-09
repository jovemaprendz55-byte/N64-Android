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
    assert(!runtime.markExecuteReturned());

    // Shutdown closes ordinary admission but must still admit the STOP lease.
    assert(runtime.beginShutdown());
    assert(runtime.phase() == Phase::StopRequested);
    assert(!runtime.tryAcquire(Operation::Execute));
    assert(!runtime.tryAcquire(Operation::Other));
    assert(!runtime.tryAcquire(Operation::Stop)); // only after shutdown request
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
    return 0;
}
