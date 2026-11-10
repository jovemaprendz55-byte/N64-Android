#pragma once

#include <cstdint>
#include <mutex>

// Small platform-independent gate for the two native shutdown phases.
// It does not issue Core commands or call EGL; JNI supplies verified facts.
class CoreShutdownPhaseGate {
public:
    enum class Phase { Idle, Preparing, Drained, Finalizing, Complete };

    struct Token {
        std::uint64_t generation = 0;
        explicit operator bool() const { return generation != 0; }
    };

    Token beginPrepare() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (phase_ == Phase::Preparing || phase_ == Phase::Drained ||
            phase_ == Phase::Finalizing)
            return {generation_};
        if (phase_ == Phase::Complete)
            return {};
        ++generation_;
        if (generation_ == 0) ++generation_;
        phase_ = Phase::Preparing;
        return {generation_};
    }

    bool markDrained(Token token) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!matches(token) || phase_ != Phase::Preparing) return false;
        phase_ = Phase::Drained;
        return true;
    }

    bool beginFinalize(Token token, bool executeReturned,
                       bool noActiveCalls, bool eglContextCurrent) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!matches(token) || phase_ != Phase::Drained ||
            !executeReturned || !noActiveCalls || !eglContextCurrent)
            return false;
        phase_ = Phase::Finalizing;
        return true;
    }

    bool finishFinalize(Token token, bool success) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!matches(token) || phase_ != Phase::Finalizing) return false;
        // A failed teardown may be retried on a valid GL thread. The caller
        // must preserve native handles and reconcile completed sub-steps.
        phase_ = success ? Phase::Complete : Phase::Drained;
        return true;
    }

    // A new Core lifecycle may start only after the previous generation
    // reached Complete. Keep the generation counter so stale callbacks from
    // the old lifecycle cannot mutate the new one.
    bool resetAfterComplete() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (phase_ != Phase::Complete) return false;
        phase_ = Phase::Idle;
        return true;
    }

    Phase phase() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return phase_;
    }

    std::uint64_t generation() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return generation_;
    }

private:
    bool matches(Token token) const {
        return token.generation != 0 && token.generation == generation_;
    }

    mutable std::mutex mutex_;
    std::uint64_t generation_ = 0;
    Phase phase_ = Phase::Idle;
};
