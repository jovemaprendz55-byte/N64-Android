#include "core_shutdown_teardown_progress.h"

#include <atomic>
#include <cassert>
#include <iostream>
#include <thread>
#include <vector>

int main() {
    using Progress = CoreShutdownTeardownProgress;
    using Step = Progress::Step;
    using State = Progress::State;

    Progress progress;
    assert(!progress.beginGeneration(0));
    assert(progress.nextStep() == Step::Count);
    assert(progress.beginGeneration(1));
    assert(!progress.beginGeneration(2)); // incomplete generation cannot be replaced

    // Teardown must be ordered. A failed native operation is not marked,
    // so retry sees exactly the same step and does not repeat earlier steps.
    assert(progress.nextStep() == Step::DetachRsp);
    assert(!progress.markComplete(1, Step::DetachGfx));
    assert(!progress.markComplete(2, Step::DetachRsp));
    assert(progress.state(Step::DetachRsp) == State::NotStarted);
    assert(progress.nextStep() == Step::DetachRsp); // retry after failure

    // Exactly one concurrent finalizer may commit a given step.
    std::atomic<bool> start{false};
    std::atomic<int> winners{0};
    std::vector<std::thread> workers;
    for (int i = 0; i < 16; ++i) {
        workers.emplace_back([&] {
            while (!start.load()) std::this_thread::yield();
            if (progress.markComplete(1, Step::DetachRsp)) ++winners;
        });
    }
    start.store(true);
    for (auto& worker : workers) worker.join();
    assert(winners.load() == 1);
    assert(progress.state(Step::DetachRsp) == State::Completed);

    const Step ordered[] = {
        Step::DetachGfx, Step::CloseRom, Step::ShutdownRsp, Step::UnloadRsp,
        Step::ShutdownGfx, Step::UnloadGfx, Step::ShutdownCore, Step::UnloadCore
    };
    for (const Step step : ordered) {
        assert(progress.nextStep() == step);
        assert(progress.markComplete(1, step));
        assert(!progress.markComplete(1, step)); // no duplicate operation record
    }
    assert(progress.complete());
    assert(progress.nextStep() == Step::Count);

    // A new generation is allowed only after all steps are resolved.
    assert(progress.beginGeneration(2));
    assert(progress.nextStep() == Step::DetachRsp);
    assert(!progress.markComplete(1, Step::DetachRsp)); // stale callback rejected

    // Missing resources are represented explicitly, not silently omitted.
    assert(progress.markSkipped(2, Step::DetachRsp));
    assert(progress.state(Step::DetachRsp) == State::Skipped);
    assert(progress.nextStep() == Step::DetachGfx);

    std::cout << "Core shutdown teardown progress tests passed\n";
    return 0;
}
