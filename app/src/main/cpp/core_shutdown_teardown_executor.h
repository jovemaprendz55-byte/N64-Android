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

        Step step = Step::Count;
        if (!progress_.tryClaimNextStep(generation_, &step)) {
            if (progress_.generation() != generation_) return Result::StaleGeneration;
            return progress_.complete() ? Result::Complete : Result::Busy;
        }

        const auto index = static_cast<std::size_t>(step);
        if (index >= callbacks_.size() || !callbacks_[index]) {
            progress_.cancelClaim(generation_, step);
            return Result::Failed;
        }

        Outcome outcome = Outcome::Failed;
        try {
            outcome = callbacks_[index]();
        } catch (...) {
            progress_.cancelClaim(generation_, step);
            return Result::Failed;
        }

        if (outcome == Outcome::Failed) {
            progress_.cancelClaim(generation_, step);
            return Result::Failed;
        }

        const bool committed = progress_.finishClaim(
            generation_, step, outcome == Outcome::Succeeded
                ? Progress::State::Completed : Progress::State::Skipped);
        if (!committed) {
            progress_.cancelClaim(generation_, step);
            return Result::StaleGeneration;
        }

        return outcome == Outcome::Succeeded ? Result::Succeeded : Result::Skipped;
    }

private:
    Progress& progress_;
    const std::uint64_t generation_;
    std::array<Callback, Progress::kStepCount> callbacks_;
    std::mutex executionMutex_;
};
