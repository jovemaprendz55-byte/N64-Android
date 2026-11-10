#include "core_shutdown_teardown_session.h"

#include <array>
#include <atomic>
#include <cassert>
#include <iostream>
#include <thread>
#include <vector>

int main() {
    using Session = CoreShutdownTeardownSession;
    using Progress = Session::Progress;
    using Step = Progress::Step;
    using Begin = Session::BeginResult;
    using Result = Session::Result;
    using Outcome = Session::Executor::Outcome;

    Session session;
    std::uint64_t generation = 0;
    int firstStepCalls = 0;
    Session::Callbacks callbacks{};
    callbacks[0] = [&] {
        ++firstStepCalls;
        return firstStepCalls == 1 ? Outcome::Failed : Outcome::Succeeded;
    };
    for (std::size_t i = 1; i < callbacks.size(); ++i)
        callbacks[i] = [] { return Outcome::Skipped; };

    assert(session.beginOrResume(callbacks, &generation) == Begin::Started);
    assert(generation == 1);
    assert(session.runNext(generation) == Result::Failed);
    assert(session.nextStep(generation) == Step::DetachRsp);

    // A repeated JNI-style entry resumes the same generation; the newly
    // supplied callbacks are ignored so completed work cannot be replaced.
    std::uint64_t resumedGeneration = 0;
    Session::Callbacks replacement{};
    replacement[0] = [] { return Outcome::Skipped; };
    assert(session.beginOrResume(replacement, &resumedGeneration) == Begin::Existing);
    assert(resumedGeneration == generation);
    assert(session.runNext(resumedGeneration) == Result::Succeeded);
    assert(firstStepCalls == 2);

    // Resolve the remaining steps and prove completion is durable.
    for (std::size_t i = 1; i < callbacks.size(); ++i)
        assert(session.runNext(generation) == Result::Skipped);
    assert(session.runNext(generation) == Result::Complete);
    assert(session.complete(generation));
    assert(session.beginOrResume(replacement, &resumedGeneration) ==
           Begin::AlreadyComplete);
    assert(resumedGeneration == generation);

    // A new generation requires the explicit new-lifecycle entry point.
    assert(session.beginNextLifecycle(callbacks, &resumedGeneration) == Begin::Started);
    assert(resumedGeneration == generation + 1);
    assert(session.runNext(generation) == Result::StaleGeneration);

    // Concurrent callers cannot run callbacks concurrently. The session
    // owner serializes calls and each step is committed exactly once.
    Session concurrent;
    std::uint64_t concurrentGeneration = 0;
    std::atomic<int> active{0};
    std::atomic<int> maximumActive{0};
    std::atomic<int> calls{0};
    Session::Callbacks concurrentCallbacks{};
    for (std::size_t i = 0; i < concurrentCallbacks.size(); ++i) {
        concurrentCallbacks[i] = [&] {
            const int now = ++active;
            int seen = maximumActive.load();
            while (seen < now && !maximumActive.compare_exchange_weak(seen, now)) {}
            ++calls;
            std::this_thread::yield();
            --active;
            return Outcome::Succeeded;
        };
    }
    assert(concurrent.beginOrResume(concurrentCallbacks, &concurrentGeneration) ==
           Begin::Started);
    std::vector<std::thread> workers;
    for (int i = 0; i < 24; ++i) {
        workers.emplace_back([&] {
            for (int attempt = 0; attempt < 3; ++attempt)
                concurrent.runNext(concurrentGeneration);
        });
    }
    for (auto& worker : workers) worker.join();
    assert(concurrent.complete(concurrentGeneration));
    assert(calls == static_cast<int>(Progress::kStepCount));
    assert(maximumActive == 1);

    // An incomplete generation cannot be replaced with a new lifecycle.
    Session incomplete;
    std::uint64_t incompleteGeneration = 0;
    Session::Callbacks pending{};
    pending[0] = [] { return Outcome::Failed; };
    assert(incomplete.beginOrResume(pending, &incompleteGeneration) == Begin::Started);
    assert(incomplete.beginNextLifecycle(pending, &resumedGeneration) == Begin::Busy);
    assert(incomplete.runNext(incompleteGeneration) == Result::Failed);

    std::cout << "Core shutdown teardown session tests passed\n";
    return 0;
}
