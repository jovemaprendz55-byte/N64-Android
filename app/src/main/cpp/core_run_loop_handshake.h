#pragma once

#include <chrono>
#include <condition_variable>
#include <mutex>

// Coordinates the Core's synchronous EXECUTE entry with a STOP request.
// The Core emits the ready marker only after run_r4300 clears its stop flag.
class CoreRunLoopHandshake {
public:
    enum class WaitResult { Ready, ExecuteReturned, Timeout };

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        ready_ = false;
        returned_ = false;
    }

    void markReady() {
        std::lock_guard<std::mutex> lock(mutex_);
        ready_ = true;
        changed_.notify_all();
    }

    void markExecuteReturned() {
        std::lock_guard<std::mutex> lock(mutex_);
        returned_ = true;
        changed_.notify_all();
    }

    template <typename Rep, typename Period>
    WaitResult waitForReadyOrReturn(const std::chrono::duration<Rep, Period>& timeout) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!changed_.wait_for(lock, timeout, [this] { return ready_ || returned_; }))
            return WaitResult::Timeout;
        return ready_ ? WaitResult::Ready : WaitResult::ExecuteReturned;
    }

    bool ready() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return ready_;
    }

private:
    mutable std::mutex mutex_;
    std::condition_variable changed_;
    bool ready_ = false;
    bool returned_ = false;
};
