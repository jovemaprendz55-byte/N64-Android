#include "core_shutdown_teardown_executor.h"

#include <algorithm>
#include <array>
#include <cassert>
#include <iostream>
#include <stdexcept>
#include <vector>

int main() {
    using Progress = CoreShutdownTeardownProgress;
    using Executor = CoreShutdownTeardownExecutor;
    using Step = Progress::Step;
    using Outcome = Executor::Outcome;
    using Result = Executor::Result;

    // RSP detach succeeds once. GFX detach fails; retry must begin at GFX.
    Progress progress;
    assert(progress.beginGeneration(10));
    int rspDetachCalls = 0;
    int gfxDetachCalls = 0;
    std::array<Executor::Callback, Progress::kStepCount> callbacks{};
    callbacks[static_cast<std::size_t>(Step::DetachRsp)] = [&] {
        ++rspDetachCalls; return Outcome::Succeeded;
    };
    callbacks[static_cast<std::size_t>(Step::DetachGfx)] = [&] {
        ++gfxDetachCalls;
        return gfxDetachCalls == 1 ? Outcome::Failed : Outcome::Succeeded;
    };
    for (std::size_t i = 2; i < callbacks.size(); ++i)
        callbacks[i] = [] { return Outcome::Skipped; };

    Executor executor(progress, 10, callbacks);
    assert(executor.runNext() == Result::Succeeded);
    assert(executor.runNext() == Result::Failed);
    assert(progress.nextStep() == Step::DetachGfx);
    assert(executor.runNext() == Result::Succeeded);
    assert(rspDetachCalls == 1 && gfxDetachCalls == 2);

    // A shutdown callback that succeeds before a later unload failure is never
    // repeated: the retry journal points only at the unload operation.
    Progress pluginProgress;
    assert(pluginProgress.beginGeneration(11));
    std::vector<Step> called;
    std::array<Executor::Callback, Progress::kStepCount> pluginCallbacks{};
    pluginCallbacks[0] = [&] { called.push_back(Step::DetachRsp); return Outcome::Skipped; };
    pluginCallbacks[1] = [&] { called.push_back(Step::DetachGfx); return Outcome::Skipped; };
    pluginCallbacks[2] = [&] { called.push_back(Step::CloseRom); return Outcome::Skipped; };
    pluginCallbacks[3] = [&] { called.push_back(Step::ShutdownRsp); return Outcome::Succeeded; };
    int unloadRspCalls = 0;
    pluginCallbacks[4] = [&] {
        called.push_back(Step::UnloadRsp);
        ++unloadRspCalls;
        return unloadRspCalls == 1 ? Outcome::Failed : Outcome::Succeeded;
    };
    for (std::size_t i = 5; i < pluginCallbacks.size(); ++i)
        pluginCallbacks[i] = [] { return Outcome::Skipped; };

    Executor pluginExecutor(pluginProgress, 11, pluginCallbacks);
    for (int i = 0; i < 4; ++i) {
        const auto result = pluginExecutor.runNext();
        assert(result == (i < 3 ? Result::Skipped : Result::Succeeded));
    }
    assert(pluginExecutor.runNext() == Result::Failed);
    assert(pluginProgress.nextStep() == Step::UnloadRsp);
    assert(pluginExecutor.runNext() == Result::Succeeded);
    assert(std::count(called.begin(), called.end(), Step::ShutdownRsp) == 1);
    assert(unloadRspCalls == 2);

    // CoreShutdown success followed by dlclose failure must retry only unload.
    Progress coreProgress;
    assert(coreProgress.beginGeneration(12));
    int coreShutdownCalls = 0;
    int coreUnloadCalls = 0;
    std::array<Executor::Callback, Progress::kStepCount> coreCallbacks{};
    for (std::size_t i = 0; i < 7; ++i)
        coreCallbacks[i] = [] { return Outcome::Skipped; };
    coreCallbacks[7] = [&] { ++coreShutdownCalls; return Outcome::Succeeded; };
    coreCallbacks[8] = [&] {
        ++coreUnloadCalls;
        return coreUnloadCalls == 1 ? Outcome::Failed : Outcome::Succeeded;
    };
    Executor coreExecutor(coreProgress, 12, coreCallbacks);
    for (int i = 0; i < 7; ++i) assert(coreExecutor.runNext() == Result::Skipped);
    assert(coreExecutor.runNext() == Result::Succeeded);
    assert(coreExecutor.runNext() == Result::Failed);
    assert(coreProgress.nextStep() == Step::UnloadCore);
    assert(coreExecutor.runNext() == Result::Succeeded);
    assert(coreShutdownCalls == 1 && coreUnloadCalls == 2);
    assert(coreExecutor.runNext() == Result::Complete);

    // Exceptions are failures and do not advance the journal.
    Progress exceptionProgress;
    assert(exceptionProgress.beginGeneration(13));
    std::array<Executor::Callback, Progress::kStepCount> exceptionCallbacks{};
    exceptionCallbacks[0] = []() -> Outcome { throw std::runtime_error("test"); };
    Executor exceptionExecutor(exceptionProgress, 13, exceptionCallbacks);
    assert(exceptionExecutor.runNext() == Result::Failed);
    assert(exceptionProgress.nextStep() == Step::DetachRsp);

    // A stale executor cannot invoke callbacks for a newer generation.
    Progress staleProgress;
    assert(staleProgress.beginGeneration(20));
    std::array<Executor::Callback, Progress::kStepCount> staleCallbacks{};
    int staleCalls = 0;
    staleCallbacks[0] = [&] { ++staleCalls; return Outcome::Succeeded; };
    Executor staleExecutor(staleProgress, 19, staleCallbacks);
    assert(staleExecutor.runNext() == Result::StaleGeneration);
    assert(staleCalls == 0);

    std::cout << "Core shutdown teardown executor tests passed\n";
    return 0;
}
