#pragma once

#include "core_shutdown_teardown_progress.h"

#include <array>
#include <functional>
#include <mutex>
#include <utility>

// Executes exactly one journal operation per call. Callbacks are injected so
// host tests can exercise retry/skip semantics without loading Core or plugins.
// The callback runs without the journal's mutex held.
class CoreShutdownTeardownExecutor {
public:
    using Step = CoreShutdownTeardownProgress::Step;
    using Progress = CoreShutdownTeardownProgress;

    enum class Outcome { Succeeded, Skipped, Failed };
    enum class Result { Succeeded, Skipped, Failed, Busy, StaleGeneration, Complete };

    using Callback = std::function<Outcome()>;

    CoreShutdownTeardownExecutor(Progress& progress, std::uint64_t generation,
                                 std::array<Callback, Progress::kStepCount> callbacks)
        : progress_(progress), generation_(generation),
          callbacks_(std::move(callbacks)) {}

    // Single-step execution keeps native side effects and journal commits
    // observable. A failed callback leaves the same step pending for retry.
    Result runNext() {
        std::unique_lock<std::mutex> executionLock(executionMutex_, std::try_to_lock);
        if (!executionLock.owns_lock()) return Result::Busy;

        if (progress_.generation() != generation_)
            return Result::StaleGeneration;

        const Step step = progress_.nextStep();
        if (step == Step::Count) {
            return progress_.complete() ? Result::Complete : Result::StaleGeneration;
        }

        const auto index = static_cast<std::size_t>(step);
        if (index >= callbacks_.size() || !callbacks_[index])
            return Result::Failed;

        Outcome outcome = Outcome::Failed;
        try {
            outcome = callbacks_[index]();
        } catch (...) {
            return Result::Failed;
        }

        if (outcome == Outcome::Failed) return Result::Failed;

        const bool committed = outcome == Outcome::Succeeded
            ? progress_.markComplete(generation_, step)
            : progress_.markSkipped(generation_, step);
        if (!committed) return Result::StaleGeneration;

        return outcome == Outcome::Succeeded ? Result::Succeeded : Result::Skipped;
    }

private:
    Progress& progress_;
    const std::uint64_t generation_;
    std::array<Callback, Progress::kStepCount> callbacks_;
    std::mutex executionMutex_;
};
