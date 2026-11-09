#pragma once

#include "core_call_gate.h"

#include <chrono>
#include <mutex>
#include <utility>

// Platform-independent lifecycle coordinator. It models Core ownership and
// admission; JNI must still keep actual handles alive for every returned lease.
class CoreRuntimeCoordinator {
public:
    enum class Phase {
        Empty,
        CoreInitialized,
        RomOpen,
        PluginsAttached,
        Executing,
        StopRequested,
        Drained,
        ShutdownComplete
    };

    enum class Operation { Execute, Stop, Other };

    class Lease {
    public:
        Lease() = default;
        explicit Lease(CoreCallGate::Lease lease) : lease_(std::move(lease)) {}
        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;
        Lease(Lease&&) noexcept = default;
        Lease& operator=(Lease&&) noexcept = default;

        explicit operator bool() const { return static_cast<bool>(lease_); }
        void release() { lease_.release(); }

    private:
        CoreCallGate::Lease lease_;
    };

    bool markCoreInitialized() {
        return transition(Phase::Empty, Phase::CoreInitialized);
    }

    bool markRomOpen() {
        return transition(Phase::CoreInitialized, Phase::RomOpen);
    }

    bool markPluginsAttached() {
        return transition(Phase::RomOpen, Phase::PluginsAttached);
    }

    bool markExecuting() {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (phase_ != Phase::PluginsAttached || gate_.shutdownRequested())
            return false;
        phase_ = Phase::Executing;
        return true;
    }

    // Called after synchronous M64CMD_EXECUTE has returned.
    bool markExecuteReturned() {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (phase_ != Phase::Executing) return false;
        phase_ = Phase::PluginsAttached;
        return true;
    }

    Lease tryAcquire(Operation operation) {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (operation == Operation::Execute && phase_ != Phase::PluginsAttached)
            return {};
        if (operation == Operation::Stop && phase_ != Phase::StopRequested)
            return {};
        if (operation == Operation::Other &&
            (phase_ == Phase::StopRequested || phase_ == Phase::Drained ||
             phase_ == Phase::ShutdownComplete))
            return {};

        const auto kind = operation == Operation::Execute
            ? CoreCallGate::Kind::Execute
            : operation == Operation::Stop
                ? CoreCallGate::Kind::Stop
                : CoreCallGate::Kind::Other;
        return Lease(gate_.tryAcquire(kind));
    }

    // Closes normal admission. The caller must then acquire a Stop lease,
    // issue M64CMD_STOP outside state/handle mutexes, and release that lease.
    bool beginShutdown() {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (phase_ == Phase::Drained || phase_ == Phase::ShutdownComplete ||
            phase_ == Phase::StopRequested)
            return false;
        phase_ = Phase::StopRequested;
        gate_.beginShutdown();
        return true;
    }

    template <typename Rep, typename Period>
    bool waitForDrainFor(const std::chrono::duration<Rep, Period>& timeout) {
        if (!gate_.waitForDrainFor(timeout)) return false;
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (phase_ != Phase::StopRequested) return false;
        phase_ = Phase::Drained;
        return true;
    }

    bool markShutdownComplete() {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (phase_ != Phase::Drained) return false;
        phase_ = Phase::ShutdownComplete;
        return true;
    }

    Phase phase() const {
        std::lock_guard<std::mutex> lock(stateMutex_);
        return phase_;
    }

    std::size_t activeCalls() const { return gate_.activeCalls(); }

private:
    bool transition(Phase expected, Phase next) {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (phase_ != expected || gate_.shutdownRequested()) return false;
        phase_ = next;
        return true;
    }

    mutable std::mutex stateMutex_;
    CoreCallGate gate_;
    Phase phase_ = Phase::Empty;
};
