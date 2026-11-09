#pragma once

#include <condition_variable>
#include <chrono>
#include <cstddef>
#include <mutex>
#include <utility>

// Small, platform-independent primitive for protecting Core calls while
// allowing M64CMD_STOP to enter during a shutdown drain.
class CoreCallGate {
public:
    enum class Kind { Execute, Stop, Other };

    class Lease {
    public:
        Lease() = default;
        explicit Lease(CoreCallGate* gate) : gate_(gate) {}
        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;

        Lease(Lease&& other) noexcept : gate_(other.gate_) {
            other.gate_ = nullptr;
        }

        Lease& operator=(Lease&& other) noexcept {
            if (this != &other) {
                release();
                gate_ = other.gate_;
                other.gate_ = nullptr;
            }
            return *this;
        }

        ~Lease() { release(); }

        explicit operator bool() const { return gate_ != nullptr; }

        void release() {
            if (gate_ == nullptr) return;
            CoreCallGate* gate = gate_;
            gate_ = nullptr;
            gate->releaseLease();
        }

    private:
        CoreCallGate* gate_ = nullptr;
    };

    Lease tryAcquire(Kind kind) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (shutdownComplete_) return {};
        if (shutdownRequested_ && kind != Kind::Stop) return {};
        ++activeCalls_;
        return Lease(this);
    }

    // Close admission before issuing STOP. STOP remains admissible so the
    // shutdown coordinator can interrupt a synchronous EXECUTE call.
    void beginShutdown() {
        std::lock_guard<std::mutex> lock(mutex_);
        shutdownRequested_ = true;
    }

    // Call only after the STOP request has returned. This waits for EXECUTE,
    // STOP, and every other previously admitted Core call to release its lease.
    void waitForDrain() {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!shutdownRequested_) return;
        drained_.wait(lock, [this] { return activeCalls_ == 0; });
        shutdownComplete_ = true;
    }

    // Bounded drain for Android lifecycle callbacks. A timeout does not
    // mark shutdown complete, so callers must preserve all Core handles.
    template <typename Rep, typename Period>
    bool waitForDrainFor(const std::chrono::duration<Rep, Period>& timeout) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!shutdownRequested_) return false;
        if (!drained_.wait_for(lock, timeout, [this] { return activeCalls_ == 0; }))
            return false;
        shutdownComplete_ = true;
        return true;
    }

    void beginShutdownAndWait() {
        beginShutdown();
        waitForDrain();
    }

    // Only call after a failed teardown has been reconciled and no lease is
    // active. This makes a later, controlled retry possible.
    bool reopenAfterFailedShutdown() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!shutdownComplete_ || activeCalls_ != 0) return false;
        shutdownRequested_ = false;
        shutdownComplete_ = false;
        return true;
    }

    bool shutdownRequested() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return shutdownRequested_;
    }

    std::size_t activeCalls() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return activeCalls_;
    }

private:
    void releaseLease() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (activeCalls_ > 0) --activeCalls_;
        if (activeCalls_ == 0) drained_.notify_all();
    }

    mutable std::mutex mutex_;
    std::condition_variable drained_;
    std::size_t activeCalls_ = 0;
    bool shutdownRequested_ = false;
    bool shutdownComplete_ = false;
};
