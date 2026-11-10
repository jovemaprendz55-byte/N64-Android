#pragma once

#include "core_shutdown_teardown_executor.h"

#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <utility>

// Process-lifetime owner for a single shutdown generation. JNI retries must
// resume this same session; only an explicit new lifecycle may start another.
// The owner lock keeps the executor object alive while runNext invokes its
// callback. The journal's own mutex is never held across native callbacks.
class CoreShutdownTeardownSession {
public:
    using Progress = CoreShutdownTeardownProgress;
    using Executor = CoreShutdownTeardownExecutor;
    using Callback = Executor::Callback;
    using Callbacks = std::array<Callback, Progress::kStepCount>;
    using Result = Executor::Result;

    enum class BeginResult { Started, Existing, AlreadyComplete, Busy };

    BeginResult beginOrResume(Callbacks callbacks, std::uint64_t* generation) {
        if (generation == nullptr) return BeginResult::Busy;
        std::lock_guard<std::mutex> lock(mutex_);
        if (executor_) {
            *generation = generation_;
            return progress_.complete()
                ? BeginResult::AlreadyComplete : BeginResult::Existing;
        }
        return startLocked(std::move(callbacks), generation);
    }

    // This entry point is deliberately explicit: callers may use it only
    // after a genuinely new Core lifecycle has been initialized. It prevents
    // a repeated JNI shutdown call from silently creating a fresh generation.
    BeginResult beginNextLifecycle(Callbacks callbacks, std::uint64_t* generation) {
        if (generation == nullptr) return BeginResult::Busy;
        std::lock_guard<std::mutex> lock(mutex_);
        if (executor_ && !progress_.complete()) {
            *generation = generation_;
            return BeginResult::Busy;
        }
        executor_.reset();
        return startLocked(std::move(callbacks), generation);
    }

    Result runNext(std::uint64_t generation) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!executor_ || generation != generation_)
            return Result::StaleGeneration;
        return executor_->runNext();
    }

    bool complete(std::uint64_t generation) const {
        std::lock_guard<std::mutex> lock(mutex_);
        return executor_ && generation == generation_ && progress_.complete();
    }

    std::uint64_t generation() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return generation_;
    }

    Progress::Step nextStep(std::uint64_t generation) const {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!executor_ || generation != generation_) return Progress::Step::Count;
        return progress_.nextStep();
    }

private:
    BeginResult startLocked(Callbacks callbacks, std::uint64_t* generation) {
        if (generation_ == std::numeric_limits<std::uint64_t>::max())
            return BeginResult::Busy;
        const std::uint64_t next = generation_ + 1;
        if (!progress_.beginGeneration(next)) return BeginResult::Busy;
        generation_ = next;
        executor_ = std::make_unique<Executor>(progress_, generation_,
                                                std::move(callbacks));
        *generation = generation_;
        return BeginResult::Started;
    }

    mutable std::mutex mutex_;
    Progress progress_;
    std::unique_ptr<Executor> executor_;
    std::uint64_t generation_ = 0;
};
