#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>

// Generation-scoped journal for the ordered native teardown operations.
// It performs no JNI, Core, plugin, EGL, or dlclose calls itself. The native
// finalizer must mark a step only after that operation has actually succeeded.
class CoreShutdownTeardownProgress {
public:
    enum class Step : std::size_t {
        DetachRsp = 0,
        DetachGfx,
        CloseRom,
        ShutdownRsp,
        UnloadRsp,
        ShutdownGfx,
        UnloadGfx,
        ShutdownCore,
        UnloadCore,
        Count
    };

    enum class State { NotStarted, Completed, Skipped };

    static constexpr std::size_t kStepCount =
        static_cast<std::size_t>(Step::Count);

    bool beginGeneration(std::uint64_t generation) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (generation == 0 || generation <= generation_) return false;
        if (inFlight_ || (active_ && !completeLocked())) return false;
        generation_ = generation;
        states_.fill(State::NotStarted);
        active_ = true;
        return true;
    }

    // Returns Count when no generation is active or all steps are resolved.
    Step nextStep() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return nextStepLocked();
    }

    // Atomically reserve the next step before its callback runs outside the
    // journal mutex. A generation cannot be replaced while this claim exists.
    bool tryClaimNextStep(std::uint64_t generation, Step* step) {
        if (step == nullptr) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if (!active_ || inFlight_ || generation != generation_) return false;
        const Step next = nextStepLocked();
        if (next == Step::Count) return false;
        inFlight_ = true;
        claimedStep_ = next;
        *step = next;
        return true;
    }

    bool finishClaim(std::uint64_t generation, Step step, State state) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!active_ || !inFlight_ || generation != generation_ ||
            claimedStep_ != step || nextStepLocked() != step) return false;
        states_[indexOf(step)] = state;
        inFlight_ = false;
        return true;
    }

    bool cancelClaim(std::uint64_t generation, Step step) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!active_ || !inFlight_ || generation != generation_ || claimedStep_ != step)
            return false;
        inFlight_ = false;
        return true;
    }

    bool markComplete(std::uint64_t generation, Step step) {
        return markResolved(generation, step, State::Completed);
    }

    // Optional resources (for example, a plugin that was never loaded) must
    // be explicitly skipped so the journal preserves the exact teardown path.
    bool markSkipped(std::uint64_t generation, Step step) {
        return markResolved(generation, step, State::Skipped);
    }

    State state(Step step) const {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto index = indexOf(step);
        return index < kStepCount ? states_[index] : State::NotStarted;
    }

    bool complete() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return active_ && completeLocked();
    }

    std::uint64_t generation() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return generation_;
    }

private:
    static constexpr std::size_t indexOf(Step step) {
        return static_cast<std::size_t>(step);
    }

    Step nextStepLocked() const {
        if (!active_) return Step::Count;
        for (std::size_t i = 0; i < kStepCount; ++i) {
            if (states_[i] == State::NotStarted)
                return static_cast<Step>(i);
        }
        return Step::Count;
    }

    bool completeLocked() const {
        for (const auto state : states_) {
            if (state == State::NotStarted) return false;
        }
        return true;
    }

    bool markResolved(std::uint64_t generation, Step step, State state) {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto index = indexOf(step);
        if (!active_ || inFlight_ || generation != generation_ || index >= kStepCount ||
            nextStepLocked() != step)
            return false;
        states_[index] = state;
        return true;
    }

    mutable std::mutex mutex_;
    std::array<State, kStepCount> states_{};
    std::uint64_t generation_ = 0;
    bool active_ = false;\n    bool inFlight_ = false;\n    Step claimedStep_ = Step::Count;
};
